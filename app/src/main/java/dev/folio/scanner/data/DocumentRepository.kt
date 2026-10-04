package dev.folio.scanner.data

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.folio.scanner.processing.*
import android.graphics.Bitmap
import java.io.InputStream

fun validatedTitle(value: String): String = value.trim().also {
    require(it.isNotEmpty()) { "Enter a name." }
    require(it.length <= 120) { "Use a name with 120 characters or fewer." }
}

@Singleton
class DocumentRepository @Inject constructor(
    private val database: FolioDatabase,
    @ApplicationContext private val context: Context,
    private val pipeline: ImagePipeline = ImagePipeline(context)
) {
    val dao = database.documents()
    val documents = dao.observeDocuments()
    val trash = dao.observeTrash()
    val pageTrash = dao.observePageTrash()
    val folders = dao.observeFolders()
    // ponytail: one mutation lock; split by document only if measured throughput needs it.
    private val mutation = Mutex()
    suspend fun snapshotOcr(id:String,target:File):Page=withContext(Dispatchers.IO) {
        val (page,input)=mutation.withLock {
            val p=requireNotNull(dao.page(id)); require(p.trashedAt==null); activeDocument(p.documentId)
            val file=File(p.originalImageUri).canonicalFile
            require(file.toPath().startsWith(directory(p.documentId).canonicalFile.toPath()))
            p to file.inputStream()
        }
        input.use { source -> target.outputStream().use { out -> source.copyTo(out,65536) } }; page
    }
    private suspend fun queueOcr(id:String) {
        if(!dev.folio.scanner.ocr.OcrWork.enabled(context)) return
        try { dao.page(id)?.let { page -> dao.save(OcrResult(id,"",System.currentTimeMillis(),status="queued")); dev.folio.scanner.ocr.OcrWork.enqueue(context,page,true) } }
        catch(_:Exception) { android.util.Log.w("FolioOCR","Could not enqueue optional OCR") }
    }
    suspend fun librarySnapshot(): LibrarySnapshot = database.withTransaction {
        val pages=dao.allPages()
        LibrarySnapshot(dao.allFolders(), dao.allDocuments(), pages,dao.allOcr().filter { o -> pages.any { it.id==o.pageId } })
    }
    /** Open at most two immutable assets under the lock; copy outside it. Open descriptors survive unlink. */
    suspend fun pinBackup(target: File): Pair<LibrarySnapshot, LibrarySnapshot> = withContext(Dispatchers.IO) {
            val snapshot=mutation.withLock { librarySnapshot() }; target.mkdirs()
            var stagedBytes=0L
            val pinned=snapshot.pages.map { page ->
                fun open(path: String): java.io.FileInputStream {
                    val source=File(path).canonicalFile
                    require(source.toPath().startsWith(directory(page.documentId).canonicalFile.toPath()) && source.isFile) { "Missing local page asset." }
                    return source.inputStream()
                }
                val inputs=mutation.withLock {
                    if(dao.page(page.id)!=page) throw java.io.IOException("Library changed while preparing backup. Retry to capture the latest pages.")
                    val original=open(page.originalImageUri)
                    try { original to open(page.processedImageUri) } catch(e:Exception) { original.close(); throw e }
                }
                suspend fun pin(input:java.io.InputStream,suffix:String):String {
                    val destination=File(target,"${page.id}-$suffix")
                    input.use { source -> destination.outputStream().use { out ->
                        val buffer=ByteArray(65536); var total=0L
                        while(true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val n=source.read(buffer); if(n<0) break
                            total+=n; stagedBytes+=n
                            require(total<=dev.folio.scanner.backup.BackupManifest.MAX_ASSET_BYTES) { "Page asset exceeds 100 MB." }
                            require(stagedBytes<=10L*1024*1024*1024) { "Expanded page data exceeds the 10 GB backup limit." }; out.write(buffer,0,n) }
                    } }
                    return destination.path
                }
                try { page.copy(originalImageUri=pin(inputs.first,"original"),processedImageUri=pin(inputs.second,"processed"),thumbnailUri="") }
                finally { inputs.first.close(); inputs.second.close() }
            }
            snapshot to snapshot.copy(pages=pinned)
    }
    /** All assets and thumbnails must be validated before this bounded, atomic publication. */
    suspend fun mergeBackup(manifest: dev.folio.scanner.backup.BackupManifest, stage: File, operation: String): Int = mutation.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            manifest.validate(); dev.folio.scanner.backup.BackupManifest.uuid(operation)
            val receipt="restore:$operation"
            dao.backupRecord(receipt)?.let { return@withContext it.bytes.toInt() }
            val journal=android.util.AtomicFile(File(stage,"merge.json"))
            val plan=if(journal.baseFile.exists() || File(stage,"merge.json.bak").exists()) org.json.JSONObject(String(journal.readFully())) else {
                val docs=org.json.JSONObject(); val pages=org.json.JSONObject(); val folders=org.json.JSONObject()
                fun alternate(id:String)=UUID.nameUUIDFromBytes("$operation/$id".toByteArray()).toString()
                manifest.documents.forEach { docs.put(it.id,if(dao.document(it.id)==null) it.id else alternate(it.id)) }
                manifest.pages.forEach { pages.put(it.page.id,if(dao.page(it.page.id)==null) it.page.id else alternate(it.page.id)) }
                manifest.folders.forEach { folder -> folders.put(folder.id,if(dao.folder(folder.id)?.let { it.name!=folder.name } == true) alternate(folder.id) else folder.id) }
                org.json.JSONObject().put("documents",docs).put("pages",pages).put("folders",folders).also { json ->
                    val out=journal.startWrite(); try { out.write(json.toString().toByteArray()); journal.finishWrite(out) } catch(e:Exception) { journal.failWrite(out); throw e }
                }
            }
            fun mapped(type:String,id:String)=plan.getJSONObject(type).getString(id).also { dev.folio.scanner.backup.BackupManifest.uuid(it) }
            val names=dao.allDocuments().filter { it.trashedAt==null }.toMutableList()
            manifest.documents.filter { it.trashedAt==null }.forEach { doc ->
                val problem=documentNameConflict(doc.title,names)
                require(problem==null) { problem.orEmpty()+" Rename the existing document before restoring this backup." }
                names+=doc
            }
            val documents=manifest.documents.map { doc ->
                val id=mapped("documents",doc.id)
                require(dao.document(id)==null) { "Restore destination changed. Retry with a new restore operation." }
                val dir=directory(id)
                if(dir.exists()) require(File(dir,"restore-owner").readText()==operation) { "Restore destination already contains local files." }
                else {
                    val ready=File(stage,"documents/${doc.id}")
                    require(File(ready,"restore-owner").readText()==operation) { "Restore files were not prepared." }
                    dir.parentFile!!.mkdirs(); check(ready.renameTo(dir)) { "Could not publish restored document files." }
                }
                doc.copy(id=id,folderId=doc.folderId?.let { mapped("folders",it) })
            }
            val pages=manifest.pages.map { entry ->
                val p=entry.page; val id=mapped("pages",p.id); val doc=mapped("documents",p.documentId)
                require(dao.page(id)==null) { "Restore page identifier conflicts with local data." }
                fun asset(name:String):String {
                    val target=File(directory(doc),name)
                    require(target.isFile) { "Prepared restore asset is missing." }
                    return target.path
                }
                val original=asset("originals/${p.id}.source")
                p.copy(id=id,documentId=doc,originalImageUri=original,
                    processedImageUri=if(entry.original==entry.processed) original else asset("processed/${p.id}.source"),thumbnailUri=asset("thumbnails/${p.id}.jpg"))
            }
            database.withTransaction {
                manifest.folders.forEach { folder -> val id=mapped("folders",folder.id); val existing=dao.folder(id)
                    require(existing==null || existing.name==folder.name) { "Restore folder conflicts with local data." }; if(existing==null) dao.save(folder.copy(id=id)) }
                documents.forEach { dao.save(it) }; pages.forEach { dao.save(it) }
                manifest.ocr.forEach { r -> val p=pages.first { it.id==mapped("pages",r.pageId) }
                    dao.save(r.copy(pageId=p.id,status=if(dev.folio.scanner.ocr.validOcr(r,p)) "complete" else "outdated")) }
                dao.save(BackupRecord(receipt,manifest.id,"",documents.size.toLong(),System.currentTimeMillis(),"complete"))
            }
            documents.size
        }
    }
    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid document identifier." }
        return File(context.filesDir, "documents/$id")
    }
    suspend fun create(title: String, folderId: String? = null): String = mutation.withLock {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.save(Document(id, uniqueTitle(title), now, now, folderId))
        id
    }
    suspend fun rename(id: String, title: String) = mutation.withLock {
        dao.save(activeDocument(id).copy(title = uniqueTitle(title, id)))
    }
    private suspend fun uniqueTitle(title: String, except: String? = null): String = validatedTitle(title).also {
        val conflict=documentNameConflict(it, dao.allDocuments(), except)
        require(conflict == null) { conflict.orEmpty() }
    }
    private suspend fun activeDocument(id: String): Document = requireNotNull(dao.document(id)) { "Document no longer exists." }.also {
        require(!it.deleting && it.trashedAt == null) { "Restore this document from Recycle Bin first." }
    }
    suspend fun renamePage(id: String, name: String) = mutation.withLock {
        val page = requireNotNull(dao.page(id)); require(page.trashedAt==null); val doc = activeDocument(page.documentId)
        database.withTransaction {
            dao.save(page.copy(pageName = pageName(name)))
            dao.save(doc.copy(modifiedAt = System.currentTimeMillis()))
        }
    }
    suspend fun favorite(id: String) = update(id) { it.copy(favorite = !it.favorite) }
    suspend fun move(id: String, folder: String?) = update(id) { it.copy(folderId = folder) }
    private suspend fun update(id: String, change: (Document) -> Document) = mutation.withLock {
        val doc = activeDocument(id)
        dao.save(change(doc).copy(modifiedAt = System.currentTimeMillis()))
    }
    suspend fun createFolder(name: String) = mutation.withLock {
        dao.save(Folder(UUID.randomUUID().toString(), validatedTitle(name)))
    }
    suspend fun renameFolder(folder: Folder, name: String) = mutation.withLock { dao.save(folder.copy(name = validatedTitle(name))) }
    suspend fun deleteFolder(id: String) = mutation.withLock { dao.deleteFolder(id) }
    suspend fun delete(id: String) = mutation.withLock {
        val doc = activeDocument(id)
        dao.save(doc.copy(trashedAt = System.currentTimeMillis()))
        withContext(Dispatchers.IO) { androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("pdf-$id").result.get() }
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("ocr-$id")
    }
    suspend fun restore(ids: Set<String>, names: Map<String,String> = emptyMap()) = mutation.withLock {
        require(ids.isNotEmpty())
        database.withTransaction {
            val active = dao.allDocuments().filter { it.trashedAt == null }.toMutableList()
            ids.forEach { id -> dao.document(id)?.let { doc ->
                val title=validatedTitle(names[id] ?: doc.title)
                require(documentNameConflict(title, active, id) == null) { documentNameConflict(title, active, id).orEmpty() }
                active += doc.copy(title=title,trashedAt = null)
            } }
            ids.forEach { id ->
                val doc = requireNotNull(dao.document(id))
                require(doc.trashedAt != null && !doc.deleting)
                val folder = doc.folderId?.takeIf { dao.folder(it) != null }
                dao.save(doc.copy(title=validatedTitle(names[id] ?: doc.title),trashedAt = null, folderId = folder))
            }
        }
    }
    /** Caller must obtain explicit confirmation; only already-trashed documents qualify. */
    suspend fun permanentlyDelete(ids: Set<String>, expiredBefore: Long? = null) = mutation.withLock {
        require(ids.isNotEmpty())
        val targets=database.withTransaction {
            val records=ids.mapNotNull { dao.document(it) }.filter { expiredBefore == null || trashExpired(it.trashedAt, expiredBefore) }
            records.forEach { doc ->
                require(doc.trashedAt != null) { "Only Recycle Bin documents can be permanently deleted." }
                dao.save(BackupRecord("local:delete:${doc.id}","",doc.id,0,System.currentTimeMillis(),"local-deleted"))
                dao.documentBackups(doc.id).forEach { record ->
                    queueDriveDeletion(dao,record.key.substringBefore(":association:").substringBefore(":document:"),doc.id,record.remoteId)
                }
                dao.save(doc.copy(deleting = true))
            }
            records.map { it.id }
        }
        withContext(Dispatchers.IO) { targets.forEach { id -> androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("pdf-$id").result.get(); purge(id) } }
    }
    suspend fun recoverDeletes() = mutation.withLock { dao.pendingDeletes().forEach { purge(it.id) }; cleanDeletedPages() }
    suspend fun cleanExpiredTrash(now: Long = System.currentTimeMillis()) {
        val ids = dao.allDocuments().filter { trashExpired(it.trashedAt, now) }.map { it.id }.toSet()
        if (ids.isNotEmpty()) permanentlyDelete(ids, now)
        val expired=dao.trashedPages().filter { trashExpired(it.trashedAt,now) && dao.document(it.documentId)?.trashedAt==null }.map { it.id }.toSet()
        if(expired.isNotEmpty()) permanentlyDeletePages(expired)
        recoverDeletes()
    }
    // Keep granted image URIs readable for a day; prune expired exports on launch/new share.
    suspend fun cleanShareCache(now: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        File(context.cacheDir,"shared-images").listFiles()?.filter { now-it.lastModified()>86_400_000L }?.forEach { check(it.deleteRecursively()) { "Could not clean expired exports." } }
    }
    suspend fun thumbnail(page: Page): String = mutation.withLock {
        withContext(Dispatchers.IO) {
            val current=dao.page(page.id) ?: return@withContext page.thumbnailUri
            val file=File(current.thumbnailUri)
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeFile(file.path,bounds)
            if(maxOf(bounds.outWidth,bounds.outHeight)<minOf(768,maxOf(current.width,current.height))) {
                val source=pipeline.decode(File(current.processedImageUri),1536)
                val scale=minOf(1f,768f/maxOf(source.width,source.height))
                val image=Bitmap.createScaledBitmap(source,(source.width*scale).toInt().coerceAtLeast(1),(source.height*scale).toInt().coerceAtLeast(1),true)
                if(image!==source) source.recycle()
                val atomic=android.util.AtomicFile(file); val stream=atomic.startWrite()
                try { check(image.compress(Bitmap.CompressFormat.JPEG,95,stream)); atomic.finishWrite(stream) }
                catch(failure: Exception) { atomic.failWrite(stream); throw failure }
                finally { image.recycle() }
            }
            file.path
        }
    }
    suspend fun recoverCaptures() = withContext(Dispatchers.IO) {
        val pending = File(context.filesDir, "pending-captures")
        pending.listFiles()?.filter { it.extension == "jpg" }?.forEach { file ->
            val id = file.name.substringBefore('_')
            val document = dao.document(id)
            if (document?.deleting == false && document.trashedAt == null) {
                val pageId = file.nameWithoutExtension.substringAfter('_')
                file.inputStream().use { stageScan(id, it, pageId) }
            }
            // A trashed document's pending capture is retained until restore or purge.
            if (document?.trashedAt == null) check(file.delete()) { "Could not clean an interrupted capture." }
        }
        pending.listFiles()?.filter { it.extension == "writing" }?.forEach { check(it.delete()) { "Could not clean incomplete camera output." } }
        File(context.filesDir, "scan-drafts").listFiles()?.filter { !File(it, "draft.json").exists() && !File(it,"draft.json.bak").exists() }?.forEach { it.deleteRecursively() }
    }
    private fun draftDirectory(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.filesDir, "scan-drafts/$id")
    }
    private fun saveDraft(draft: ScanDraft) {
        val json = org.json.JSONObject().put("id", draft.id).put("document", draft.documentId).put("crop", draft.crop)
            .put("replacement", draft.replacement ?: "").put("detected", draft.detected)
        val atomic = android.util.AtomicFile(File(draftDirectory(draft.id), "draft.json")); val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray()); atomic.finishWrite(stream) } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
    suspend fun draft(id: String): ScanDraft = withContext(Dispatchers.IO) {
        val dir = draftDirectory(id)
        val json = org.json.JSONObject(String(android.util.AtomicFile(File(dir, "draft.json")).readFully()))
        ScanDraft(id, json.getString("document"), File(dir, "original.source").path, json.getString("crop"), json.optString("replacement").takeIf { it.isNotEmpty() }, json.optBoolean("detected"))
    }
    suspend fun drafts(document: String): List<ScanDraft> = withContext(Dispatchers.IO) {
        File(context.filesDir, "scan-drafts").listFiles().orEmpty().mapNotNull { runCatching { draft(it.name) }.getOrNull() }.filter { it.documentId == document }
    }
    suspend fun stageScan(document: String, input: InputStream, id: String = UUID.randomUUID().toString(), replacement: String? = null): String = mutation.withLock {
        withContext(Dispatchers.IO) {
            activeDocument(document)
            replacement?.let { require(dao.page(it)?.documentId == document) }
            val dir = draftDirectory(id).apply { mkdirs() }
            if (File(dir, "draft.json").exists()) return@withContext id
            val source = File(dir, "original.source"); val writing = File(dir, "original.writing")
            try {
                writing.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 100L * 1024 * 1024) { "Choose an image smaller than 100 MB." }; out.write(buffer, 0, count) }
                }
                check(writing.renameTo(source)) { "Could not retain capture." }
                val initial = ScanDraft(id, document, source.path, encodeCorners(Geometry.full), replacement)
                saveDraft(initial)
                val image = pipeline.decode(source, 1600)
                val corners = try { pipeline.detect(image) } finally { image.recycle() }
                saveDraft(initial.copy(crop = encodeCorners(corners ?: Geometry.full), detected = corners != null))
                id
            } catch (error: Exception) {
                if (!File(dir, "draft.json").exists()) withContext(NonCancellable) { dir.deleteRecursively() }
                throw error
            }
        }
    }
    suspend fun cropDraft(id: String, corners: List<Corner>, detected: Boolean = false) = mutation.withLock {
        withContext(Dispatchers.IO) { val scan=draft(id); activeDocument(scan.documentId); require(Geometry.valid(corners)); saveDraft(scan.copy(crop = encodeCorners(corners), detected = detected)) }
    }
    suspend fun discardDraft(id: String) {
        val draft = draft(id)
        dao.page(id)?.let { deletePages(draft.documentId, setOf(id)); permanentlyDeletePages(setOf(id)) }
        mutation.withLock { withContext(Dispatchers.IO) { check(draftDirectory(id).deleteRecursively()) { "Could not remove unfinished scan." } } }
    }
    suspend fun acceptDraft(id: String, enhancement: Enhancement, rotation: Int, layout: PageLayout? = null): String = withContext(NonCancellable) {
        val draft = draft(id)
        File(draft.original).inputStream().use { importImage(draft.documentId, it, id, detectDocument = false) }
        edit(id, decodeCorners(draft.crop), enhancement, rotation, enqueueOcr=false, layout=layout ?: draft.replacement?.let { dao.page(it)?.layout() })
        draft.replacement?.takeIf { dao.page(it) != null }?.let { replacePage(it, id, inheritSize=false) }
        withContext(Dispatchers.IO) { check(draftDirectory(id).deleteRecursively()) { "Scan saved; temporary cleanup failed." } }
        queueOcr(id)
        id
    }
    private suspend fun purge(id: String) = withContext(Dispatchers.IO) {
        androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag("ocr-$id").result.get()
        drafts(id).forEach { check(draftDirectory(it.id).deleteRecursively()) { "Could not remove unfinished scan." } }
        val dir = directory(id)
        check(!dir.exists() || dir.deleteRecursively()) { "Could not remove document files. Try again." }
        File(context.filesDir, "pending-captures").listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { check(it.delete()) { "Could not remove pending capture." } }
        val jobs = File(context.filesDir, "pdf-jobs").canonicalFile
        jobs.listFiles()?.filter { File(it, "document").takeIf { marker -> marker.isFile }?.readText() == id || File(it,"documents").takeIf { marker -> marker.isFile }?.readLines()?.contains(id)==true }?.forEach {
            require(it.canonicalFile.toPath().startsWith(jobs.toPath())); check(it.deleteRecursively()) { "Could not remove PDF job inputs." }
        }
        File(context.cacheDir,"shared-pdfs").listFiles()?.filter { File(it,"documents").takeIf { marker -> marker.isFile }?.readLines()?.contains(id)==true }?.forEach { check(it.deleteRecursively()) { "Could not remove prepared PDF exports." } }
        File(context.cacheDir, "shared-images").listFiles()?.filter { File(it, "document").takeIf { marker -> marker.isFile }?.readText() == id }?.forEach {
            check(it.deleteRecursively()) { "Could not remove exported images." }
        }
        dao.deleteDocument(id)
    }
    suspend fun duplicate(id: String, title: String? = null): String = mutation.withLock {
        withContext(Dispatchers.IO) {
            val original = activeDocument(id)
            val copyTitle = uniqueTitle(title ?: original.title.take(113) + " (copy)")
            val newId = UUID.randomUUID().toString()
            val from = directory(id)
            val to = directory(newId)
            try {
                if (from.exists()) from.copyRecursively(to, overwrite = false)
                database.withTransaction {
                    val now = System.currentTimeMillis()
                    dao.save(original.copy(id = newId, title = copyTitle, createdAt = now, modifiedAt = now))
                    dao.pages(id).forEach { page ->
                        val pageId = UUID.randomUUID().toString()
                        fun relocated(path: String): String = if (path.isEmpty()) path else {
                            val source = File(path).canonicalFile
                            require(source.toPath().startsWith(from.canonicalFile.toPath()))
                            File(to, source.relativeTo(from.canonicalFile).path).path
                        }
                        dao.save(page.copy(id = pageId, documentId = newId,
                            originalImageUri = relocated(page.originalImageUri), processedImageUri = relocated(page.processedImageUri), thumbnailUri = relocated(page.thumbnailUri)))
                        dao.ocr(page.id)?.let { dao.save(it.copy(pageId = pageId)) }
                        dao.annotations(page.id).forEach { dao.save(it.copy(id = UUID.randomUUID().toString(), pageId = pageId)) }
                    }
                    dao.pdfs(id).forEach { pdf ->
                        dao.save(pdf.copy(id = UUID.randomUUID().toString(), documentId = newId, operationId = UUID.randomUUID().toString(),
                            path = File(to, File(pdf.path).relativeTo(from).path).path))
                    }
                }
                newId
            } catch (error: Exception) {
                withContext(NonCancellable) { if (dao.document(newId) == null) to.deleteRecursively() }
                throw error
            }
        }
    }
    suspend fun reorder(documentId: String, ids: List<String>) = mutation.withLock {
        activeDocument(documentId)
        database.withTransaction {
            val existing = dao.pages(documentId)
            require(ids.size == existing.size && ids.toSet() == existing.map { it.id }.toSet()) { "Page order is invalid." }
            ids.forEachIndexed { index, id -> dao.position(id, -index - 1) }
            ids.forEachIndexed { index, id -> dao.position(id, index) }
            val doc = requireNotNull(dao.document(documentId))
            dao.save(doc.copy(pageCount = ids.size, modifiedAt = System.currentTimeMillis()))
        }
    }
    suspend fun importImage(documentId: String, input: InputStream, captureId: String? = null, detectDocument: Boolean = true): String = mutation.withLock {
        withContext(Dispatchers.IO) {
            val doc = requireNotNull(dao.document(documentId)) { "Document no longer exists." }
            require(!doc.deleting && doc.trashedAt == null)
            val pageId = captureId ?: UUID.randomUUID().toString()
            require(UUID.fromString(pageId).toString() == pageId)
            if (dao.page(pageId) != null) return@withContext pageId
            val dir = directory(documentId)
            val original = File(dir, "originals/$pageId.source").apply { parentFile!!.mkdirs() }
            val processed = File(dir, "processed/$pageId.jpg").apply { parentFile!!.mkdirs() }
            val thumbnail = File(dir, "thumbnails/$pageId.jpg").apply { parentFile!!.mkdirs() }
            val fallbackThumbnail = File(dir, "thumbnails/$pageId-original.jpg")
            var bitmap: Bitmap? = null
            var result: Bitmap? = null
            try {
                original.outputStream().use { out ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break
                        total += read; require(total <= 100L * 1024 * 1024) { "Choose an image smaller than 100 MB." }; out.write(buffer, 0, read) }
                }
                bitmap = pipeline.decode(original)
                writeThumbnail(bitmap, fallbackThumbnail)
                val originalWidth=bitmap.width; val originalHeight=bitmap.height
                database.withTransaction {
                    val pages = dao.pages(documentId)
                    dao.save(Page(pageId, documentId, pages.size, original.path, original.path, fallbackThumbnail.path, originalWidth, originalHeight, crop = encodeCorners(Geometry.full)))
                    dao.save(doc.copy(pageCount = pages.size + 1, modifiedAt = System.currentTimeMillis()))
                }
                val corners = if (detectDocument) pipeline.detect(bitmap) ?: Geometry.full else Geometry.full
                bitmap.recycle(); bitmap = null
                result = pipeline.correct(original, corners)
                writeJpeg(result, processed)
                writeThumbnail(result, thumbnail)
                database.withTransaction {
                    val page = requireNotNull(dao.page(pageId))
                    dao.save(page.copy(processedImageUri = processed.path, thumbnailUri = thumbnail.path, width = result.width, height = result.height, crop = encodeCorners(corners)))
                }
                discardUnreferenced(documentId, listOf(fallbackThumbnail))
                pageId
            } catch (failure: Exception) {
                discardUnreferenced(documentId, listOf(original, processed, thumbnail, fallbackThumbnail))
                throw failure
            } finally { bitmap?.recycle(); result?.recycle() }
        }
    }
    suspend fun crop(pageId: String, corners: List<Corner>) = edit(pageId, corners = corners)
    suspend fun edit(pageId: String, corners: List<Corner>? = null, enhancement: Enhancement? = null, rotation: Int? = null, enqueueOcr:Boolean=true, layout: PageLayout? = null) = mutation.withLock {
        withContext(Dispatchers.IO) {
            val page = requireNotNull(dao.page(pageId)) { "Page no longer exists." }; require(page.trashedAt==null) { "Restore this page from Recycle Bin first." }
            activeDocument(page.documentId)
            val crop = corners ?: decodeCorners(page.crop)
            val config = enhancement ?: Enhancement.decode(page.enhancement)
            val angle = rotation ?: page.rotation
            val corrected = pipeline.correct(File(page.originalImageUri), crop)
            val paper = layout ?: page.layout()
            val enhanced = try { pipeline.enhance(corrected, config, angle) } finally { corrected.recycle() }
            val result = try { pipeline.pageCanvas(enhanced, paper) } catch(failure:Throwable) { enhanced.recycle(); throw failure }
            if(result !== enhanced) enhanced.recycle()
            val version = UUID.randomUUID().toString()
            val file = File(directory(page.documentId), "processed/$version.jpg")
            val thumb = File(directory(page.documentId), "thumbnails/$version.jpg")
            try {
                writeJpeg(result, file); writeThumbnail(result, thumb)
                database.withTransaction {
                    dao.save(page.copy(processedImageUri = file.path, thumbnailUri = thumb.path, crop = encodeCorners(crop), width = result.width, height = result.height, rotation = angle, enhancement = config.encode(), pageSize = paper.size, pageFit = paper.fit, pageWidthMm = paper.widthMm, pageHeightMm = paper.heightMm))
                    dao.clearOcr(pageId)
                    val doc = requireNotNull(dao.document(page.documentId))
                    dao.save(doc.copy(modifiedAt = System.currentTimeMillis()))
                }
                discardUnreferenced(page.documentId, listOf(File(page.processedImageUri), File(page.thumbnailUri)))
                if(enqueueOcr) queueOcr(pageId)
            } catch (failure: Exception) { discardUnreferenced(page.documentId, listOf(file, thumb)); throw failure }
            finally { result.recycle() }
        }
    }
    suspend fun deletePages(documentId: String, ids: Set<String>) = mutation.withLock {
        val doc=activeDocument(documentId)
        database.withTransaction {
            val pages=dao.pages(documentId)
            require(ids.isNotEmpty() && ids.all { id -> pages.any { it.id==id } }) { "Select existing pages." }
            var position=maxOf(1_000_000,dao.storedPages(documentId).maxOfOrNull { it.position } ?: 0)
            pages.filter { it.id in ids }.forEach { page ->
                dao.save(page.copy(position=++position,trashedAt=System.currentTimeMillis(),trashPosition=page.position,trashDocumentTitle=doc.title))
            }
            orderPages(documentId,pages.filter { it.id !in ids })
        }
        ids.forEach { androidx.work.WorkManager.getInstance(context).cancelUniqueWork("ocr-page-$it") }
    }
    private suspend fun orderPages(document:String,pages:List<Page>) {
        pages.forEachIndexed { i,p -> dao.position(p.id,-i-1) }
        pages.forEachIndexed { i,p -> dao.position(p.id,i) }
        dao.document(document)?.let { dao.save(it.copy(pageCount=pages.size,modifiedAt=System.currentTimeMillis())) }
    }
    suspend fun restorePages(ids:Set<String>) = mutation.withLock {
        database.withTransaction {
            val restored=ids.map { requireNotNull(dao.page(it)) { "Page no longer exists." } }
            require(restored.all { it.trashedAt!=null }) { "Select Recycle Bin pages." }
            restored.groupBy { it.documentId }.forEach { (document,pages) ->
                activeDocument(document)
                val active=dao.pages(document).toMutableList()
                pages.sortedWith(compareBy({it.trashPosition},{it.trashedAt})).forEach { page ->
                    active.add((page.trashPosition ?: active.size).coerceIn(0,active.size),page)
                    dao.save(page.copy(trashedAt=null,trashPosition=null,trashDocumentTitle=null))
                }
                orderPages(document,active)
            }
        }
    }
    suspend fun permanentlyDeletePages(ids: Set<String>) = mutation.withLock {
        withContext(Dispatchers.IO) {
            val targets=ids.mapNotNull { dao.page(it) }
            require(ids.isNotEmpty() && targets.all { it.trashedAt!=null }) { "Only Recycle Bin pages can be permanently deleted." }
            targets.groupBy { it.documentId }.forEach { (documentId,removed) ->
            val pages=dao.storedPages(documentId)
            val pageIds=removed.map { it.id }.toSet()
            database.withTransaction {
                val associations=dao.documentBackups(documentId)
                removed.forEach { p ->
                    val paths=org.json.JSONArray(listOf(p.originalImageUri,p.processedImageUri,p.thumbnailUri).distinct())
                    dao.save(BackupRecord("local:delete:page:${p.id}:$documentId","",p.id,0,System.currentTimeMillis(),"local-page-cleanup",paths.toString()))
                    associations.forEach { record -> queueDrivePageDeletion(dao,record.key.substringBefore(":association:").substringBefore(":document:"),p.id,documentId,record.remoteId) }
                    dao.deletePage(p.id)
                }
                val remaining = pages.filter { it.id !in pageIds && it.trashedAt==null }
                remaining.forEachIndexed { i, p -> dao.position(p.id, -i - 1) }
                remaining.forEachIndexed { i, p -> dao.position(p.id, i) }
                val doc = requireNotNull(dao.document(documentId))
                dao.save(doc.copy(pageCount = remaining.size, modifiedAt = System.currentTimeMillis()))
            }
            }
            withContext(NonCancellable) { cleanDeletedPages() }
        }
    }
    private suspend fun cleanDeletedPages() = withContext(Dispatchers.IO) {
        dao.driveDeletions().filter { it.key.startsWith("local:delete:page:") && it.state=="local-page-cleanup" }.forEach { receipt ->
            val document=receipt.key.substringAfter("local:delete:page:").split(':')[1]
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("ocr-page-${receipt.hash}").result.get()
            val ocrInput=File(context.cacheDir,"ocr-input-${receipt.hash}.source")
            check(!ocrInput.exists() || ocrInput.delete()) { "Page removed; OCR input cleanup will retry on restart." }
            val paths=org.json.JSONArray(receipt.session)
            val referenced=dao.storedPages(document).flatMap { listOf(it.originalImageUri,it.processedImageUri,it.thumbnailUri) }.map { File(it).canonicalFile }.toSet()
            repeat(paths.length()) { index ->
                val file=File(paths.getString(index)).canonicalFile
                require(file.toPath().startsWith(directory(document).canonicalFile.toPath()))
                if(file !in referenced) check(!file.exists() || file.delete()) { "Page removed; file cleanup will retry on restart." }
            }
            dao.save(receipt.copy(state="local-page-deleted",session=""))
        }
    }
    suspend fun duplicatePage(pageId: String): String = mutation.withLock {
        withContext(Dispatchers.IO) {
            val page = requireNotNull(dao.page(pageId)); require(page.trashedAt==null)
            activeDocument(page.documentId)
            val newId = UUID.randomUUID().toString()
            val dir = directory(page.documentId)
            val originals = File(dir, "originals/$newId.source")
            val processed = File(dir, "processed/$newId.jpg")
            val thumbnail = File(dir, "thumbnails/$newId.jpg")
            try {
                File(page.originalImageUri).copyTo(originals); File(page.processedImageUri).copyTo(processed); File(page.thumbnailUri).copyTo(thumbnail)
                database.withTransaction {
                    val pages = dao.pages(page.documentId)
                    dao.save(page.copy(id = newId, position = pages.size, originalImageUri = originals.path, processedImageUri = processed.path, thumbnailUri = thumbnail.path))
                    dao.ocr(pageId)?.let { dao.save(it.copy(pageId = newId)) }
                    dao.annotations(pageId).forEach { dao.save(it.copy(id = UUID.randomUUID().toString(), pageId = newId)) }
                    val doc = requireNotNull(dao.document(page.documentId))
                    dao.save(doc.copy(pageCount = pages.size + 1, modifiedAt = System.currentTimeMillis()))
                }
                newId
            } catch (failure: Exception) { discardUnreferenced(page.documentId, listOf(originals, processed, thumbnail)); throw failure }
        }
    }
    suspend fun replacePage(oldId: String, newId: String, inheritSize: Boolean = true) {
        if(inheritSize) {
            val paper=mutation.withLock { val old=requireNotNull(dao.page(oldId)); val replacement=requireNotNull(dao.page(newId)); require(old.documentId==replacement.documentId); activeDocument(old.documentId); old.layout() }
            if(dao.page(newId)?.layout()!=paper) edit(newId,layout=paper)
        }
        mutation.withLock {
        database.withTransaction {
            val old = requireNotNull(dao.page(oldId)); val replacement = requireNotNull(dao.page(newId))
            require(old.documentId == replacement.documentId && oldId != newId)
            dao.deletePage(oldId)
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("ocr-page-$oldId")
            dao.save(replacement.copy(position = old.position, pageName = old.pageName))
            val pages = dao.pages(old.documentId)
            pages.forEachIndexed { i, p -> dao.position(p.id, -i - 1) }
            pages.forEachIndexed { i, p -> dao.position(p.id, i) }
            val doc = requireNotNull(dao.document(old.documentId))
            dao.save(doc.copy(pageCount = pages.size, modifiedAt = System.currentTimeMillis()))
        }
        // Retain superseded originals until document deletion; replacement never destroys source captures.
        }
    }
    private fun writeJpeg(bitmap: Bitmap, file: File) {
        file.parentFile!!.mkdirs()
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Could not save image. Check available storage." } }
    }
    suspend fun snapshotImages(documentId: String, target: File, selected: Set<String>? = null, layouts: MutableList<PageLayout>? = null, ordered:List<String>?=null): List<File> = mutation.withLock {
        withContext(Dispatchers.IO) {
            activeDocument(documentId)
            val all = dao.pages(documentId)
            require(selected == null || selected.isNotEmpty() && selected.all { id -> all.any { it.id == id } }) { "Select existing pages." }
            require(ordered==null || ordered.isNotEmpty() && ordered.distinct().size==ordered.size && ordered.all { id -> all.any { it.id==id } }) { "Select existing pages once." }
            val pages = ordered?.map { id -> all.first { it.id==id } } ?: all.filter { selected == null || it.id in selected }; require(pages.size in 1..500) { "Add 1 to 500 pages." }
            layouts?.addAll(pages.map { it.layout() })
            target.mkdirs()
            if (target.canonicalFile.toPath().startsWith(File(context.cacheDir,"shared-images").canonicalFile.toPath())) File(target,"document").writeText(documentId)
            pages.mapIndexed { i, page ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val file = File(target, "$i.jpg")
                if (page.processedImageUri == page.originalImageUri) {
                    // An interrupted image import may still reference its EXIF-bearing original.
                    val bitmap = pipeline.decode(File(page.originalImageUri))
                    try { writeJpeg(bitmap, file) } finally { bitmap.recycle() }
                    file
                } else File(page.processedImageUri).copyTo(file, overwrite = true)
            }
        }
    }
    suspend fun snapshotPdf(id: String, target: File): PdfAsset = mutation.withLock {
        withContext(Dispatchers.IO) { val pdf = requireNotNull(dao.pdf(id)); activeDocument(pdf.documentId); File(pdf.path).copyTo(target, overwrite = true); pdf }
    }
    suspend fun publishPdfs(documentId: String, assets: List<PdfAsset>) = mutation.withLock {
        withContext(Dispatchers.IO) {
            activeDocument(documentId)
            val placed = mutableListOf<File>()
            try {
                val ready = assets.map { asset ->
                    val target = File(directory(documentId), "pdfs/${asset.id}.pdf").apply { parentFile!!.mkdirs() }
                    check(File(asset.path).renameTo(target)) { "Could not save PDF. Check storage." }; placed += target
                    asset.copy(path = target.path)
                }
                database.withTransaction { ready.forEach { dao.save(it) } }
            } catch (error: Exception) {
                withContext(NonCancellable) { placed.forEach { file -> if (assets.none { dao.pdf(it.id)?.path == file.path }) file.delete() } }
                throw error
            }
        }
    }
    suspend fun deletePdf(id: String) = mutation.withLock {
        withContext(Dispatchers.IO) {
            val pdf = requireNotNull(dao.pdf(id)); val file = File(pdf.path).canonicalFile
            require(file.toPath().startsWith(directory(pdf.documentId).canonicalFile.toPath()))
            check(!file.exists() || file.delete()) { "Could not delete PDF. Check storage." }; dao.deletePdf(id)
        }
    }
    private suspend fun discardUnreferenced(documentId: String, files: List<File>) = withContext(NonCancellable + Dispatchers.IO) {
        // A cancelled continuation may follow a committed transaction. Consult Room before removing files.
        val referenced = dao.storedPages(documentId).flatMap { listOf(it.originalImageUri, it.processedImageUri, it.thumbnailUri) }.map { File(it).canonicalPath }.toSet()
        val root = directory(documentId).canonicalFile.toPath()
        files.forEach { candidate ->
            val file = candidate.canonicalFile
            require(file.toPath().startsWith(root)) { "File does not belong to this document." }
            if (file.path !in referenced && file.exists()) check(file.delete()) { "Could not clean unused page files." }
        }
    }
    private fun writeThumbnail(bitmap: Bitmap, file: File) {
        val scale = minOf(1f, 768f / maxOf(bitmap.width, bitmap.height))
        val thumb = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        try { writeJpeg(thumb, file) } finally { if (thumb !== bitmap) thumb.recycle() }
    }
}

fun encodeCorners(points: List<Corner>): String = points.joinToString(";") { "${it.x},${it.y}" }
fun decodeCorners(value: String): List<Corner> = runCatching {
    value.split(';').map { val xy = it.split(','); Corner(xy[0].toDouble(), xy[1].toDouble()) }.also { require(Geometry.valid(it)) }
}.getOrDefault(Geometry.full)

package dev.folio.scanner.pdf

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.scanner.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfRepository @Inject constructor(@ApplicationContext val context: Context, val documents: DocumentRepository, val engine: PdfEngine) {
    // ponytail: serialize PDF processing to bound bitmap/native memory; profile before parallelizing.
    private val processing = Mutex()
    val work get() = WorkManager.getInstance(context)
    fun jobs(documentId: String) = work.getWorkInfosByTagFlow("pdf-$documentId")
    fun folder(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.filesDir, "pdf-jobs/$id")
    }
    private fun cipher(mode: Int, iv: ByteArray? = null): Cipher {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (store.getKey("folio-pdf-requests", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("folio-pdf-requests", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { if (iv == null) init(mode, key) else init(mode, key, GCMParameterSpec(128, iv)) }
    }
    private fun saveRequest(id: String, request: JSONObject) {
        val cipher = cipher(Cipher.ENCRYPT_MODE)
        val bytes = cipher.iv + cipher.doFinal(request.toString().toByteArray(Charsets.UTF_8))
        val atomic = AtomicFile(File(folder(id), "request")); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (error: Exception) { atomic.failWrite(out); throw error }
    }
    fun request(id: String): JSONObject {
        val bytes = AtomicFile(File(folder(id), "request")).readFully()
        return JSONObject(String(cipher(Cipher.DECRYPT_MODE, bytes.copyOfRange(0, 12)).doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    private suspend fun enqueue(id: String, document: String) = withContext(Dispatchers.IO) {
        val builder=OneTimeWorkRequestBuilder<PdfWorker>().setInputData(workDataOf("operation" to id)).addTag("pdf-$document").addTag("operation-$id").addTag("folio-pdf-share")
        request(id).optJSONArray("sourceDocuments")?.let { a -> repeat(a.length()) { builder.addTag("pdf-${a.getString(it)}") } }
        val task=builder.build()
        work.enqueueUniqueWork("operation-$id", ExistingWorkPolicy.KEEP, task).result.get()
        File(folder(id), "scheduled").writeText("1")
    }
    suspend fun start(document: String, kind: String, title: String, pdfs: List<String> = emptyList(),
                      selection: String = "", rotations: List<Int> = emptyList(), splitSize: Int = 1,
                      quality: PdfQuality = PdfQuality.ORIGINAL, password: String = "", sourcePassword: String = "", destination: String = "", selectedPages: Set<String>? = null, sourceDocuments: List<String> = listOf(document)): String {
        val id = prepare(document, kind, title, pdfs, selection, rotations, splitSize, quality, password, sourcePassword, destination, selectedPages, sourceDocuments)
        enqueue(id, document); return id
    }
    suspend fun prepare(document: String, kind: String, title: String, pdfs: List<String> = emptyList(),
                      selection: String = "", rotations: List<Int> = emptyList(), splitSize: Int = 1,
                      quality: PdfQuality = PdfQuality.ORIGINAL, password: String = "", sourcePassword: String = "", destination: String = "", selectedPages: Set<String>? = null, sourceDocuments: List<String> = listOf(document)): String = withContext(Dispatchers.IO) {
        require(kind in listOf("generate", "combine", "split", "import", "export"))
        val id = UUID.randomUUID().toString(); val dir = folder(id).apply { mkdirs() }
        try {
            File(dir, "document").writeText(document)
            File(dir,"documents").writeText(sourceDocuments.joinToString("\n"))
            val request = JSONObject().put("document", document).put("kind", kind).put("title", validatedTitle(title))
                .put("quality", quality.name).put("password", password).put("sourcePassword", sourcePassword).put("splitSize", splitSize).put("destination", destination)
            if (kind == "generate") {
                require(sourceDocuments.size in 1..50 && sourceDocuments.distinct().size==sourceDocuments.size && sourceDocuments.first()==document) { "Select up to 50 different documents." }
                require(sourceDocuments.size==1 || selectedPages==null)
                val layouts = mutableListOf<PageLayout>()
                val images=sourceDocuments.flatMapIndexed { index,source -> documents.snapshotImages(source,File(dir,"inputs/$index"),selectedPages,layouts) }
                require(images.size<=500) { "Choose up to 500 pages for a combined document." }
                request.put("sourceDocuments",JSONArray(sourceDocuments))
                request.put("images", JSONArray(images.map { it.path }))
                request.put("layouts", JSONArray(layouts.map { JSONObject().put("size",it.size).put("fit",it.fit).put("width",it.widthMm).put("height",it.heightMm) }))
            }
            else {
                require(pdfs.isNotEmpty() && pdfs.size <= 50) { "Choose up to 50 PDFs." }
                val sources = JSONArray()
                pdfs.forEachIndexed { index, pdf ->
                    val file = File(dir, "source-$index.pdf"); snapshotResult(pdf, file)
                    val count = engine.count(file, sourcePassword)
                    val pages = if (pdfs.size == 1) pageSelection(selection, count) else (1..count).toList()
                    val angles = if (rotations.isEmpty()) List(pages.size) { 0 } else rotations.also { require(it.size == pages.size) }
                    sources.put(JSONObject().put("file", file.path).put("pages", JSONArray(pages)).put("rotations", JSONArray(angles)))
                }
                request.put("sources", sources)
            }
            saveRequest(id, request); ensureActive(); id
        } catch (error: Exception) { withContext(NonCancellable) { dir.deleteRecursively() }; throw error }
    }
    suspend fun retry(operation: String) = withContext(Dispatchers.IO) {
        val document=documents.dao.document(request(operation).getString("document"))
        require(document!=null && !document.deleting && document.trashedAt==null) { "Restore this document from Recycle Bin first." }
        require(work.getWorkInfosByTagFlow("operation-$operation").first().all { it.state.isFinished }) { "This operation is already queued or running." }
        enqueue(operation, request(operation).getString("document"))
    }
    suspend fun discard(operation: String) = withContext(Dispatchers.IO) { folder(operation).deleteRecursively() }
    suspend fun cleanCompleted(operation: String) = withContext(Dispatchers.IO) {
        folder(operation).listFiles()?.filter { it.name !in listOf("complete", "document") }?.forEach { it.deleteRecursively() }
    }
    suspend fun recoverPrepared() = withContext(Dispatchers.IO) {
        File(context.filesDir, "pdf-jobs").listFiles()?.forEach { dir ->
            val id = dir.name
            if (File(dir, "request").exists()) {
                val document=documents.dao.document(request(id).getString("document"))
                if(document?.trashedAt!=null) return@forEach // Retain cancelled/prepared inputs for restore/retry.
                val infos = work.getWorkInfosByTagFlow("operation-$id").first()
                if (infos.isEmpty() && File(dir, "scheduled").exists()) {
                    // WorkManager pruned finished history. Never silently restart a cancelled job.
                    dir.deleteRecursively()
                } else if (infos.isEmpty()) enqueue(id, request(id).getString("document"))
                else File(dir, "scheduled").writeText("1")
            } else if (!File(dir, "request").exists() && !File(dir, "complete").exists()) dir.deleteRecursively()
        }
    }
    suspend fun forget(operation: String) {
        require(work.getWorkInfosByTagFlow("operation-$operation").first().all { it.state.isFinished }) { "Cancel the running operation before discarding inputs." }
        discard(operation); work.pruneWork()
    }
    suspend fun add(document: String, uri: Uri, title: String, password: String, decryptForMerge:Boolean=false): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString(); val dir = folder(id).apply { mkdirs() }; val file = File(dir, "import.pdf")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { out ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { ensureActive(); val size = input.read(buffer); if (size < 0) break
                    total += size; require(total <= 500L * 1024 * 1024) { "Choose a PDF smaller than 500 MB." }; out.write(buffer, 0, size) }
            } }
            val count = engine.count(file, password)
            var protected = engine.read(file, password).use { it.reader.isEncrypted }
            if(decryptForMerge && protected) {
                val copy=File(dir,"merge.pdf"); val job=currentCoroutineContext()
                engine.combine(listOf(PdfSource(file,(1..count).toList(),password=password)),copy,title) { _,_ -> job.ensureActive() }
                check(file.delete() && copy.renameTo(file)) { "Could not retain merge input." }
                protected=false
            }
            publishResults(id, listOf(PdfAsset(id, document, id, validatedTitle(title), file.path, count, System.currentTimeMillis(), protected)))
            id
        } finally { withContext(NonCancellable) { dir.deleteRecursively() } }
    }
    suspend fun results(operation:String):List<PdfAsset> = withContext(Dispatchers.IO) {
        val file=File(context.cacheDir,"shared-pdfs/$operation/results.json")
        if(!file.exists()) emptyList() else JSONArray(file.readText()).let { a -> List(a.length()) { i -> val j=a.getJSONObject(i); PdfAsset(j.getString("id"),j.getString("document"),operation,j.getString("title"),j.getString("path"),j.getInt("count"),j.getLong("created"),j.getBoolean("protected")) } }
    }
    suspend fun snapshotResult(id:String,target:File):PdfAsset=withContext(Dispatchers.IO) {
        val asset=File(context.cacheDir,"shared-pdfs").listFiles().orEmpty().flatMap { results(it.name) }.firstOrNull { it.id==id }
            ?: documents.dao.pdf(id) ?: error("Prepared PDF expired. Generate it again.")
        File(asset.path).copyTo(target,overwrite=true); asset
    }
    private suspend fun publishResults(operation:String,assets:List<PdfAsset>) {
        val dir=File(context.cacheDir,"shared-pdfs/$operation").apply { mkdirs() }
        File(dir,"documents").writeText(File(folder(operation),"documents").takeIf { it.isFile }?.readText() ?: assets.map { it.documentId }.distinct().joinToString("\n"))
        val ready=assets.map { it.copy(path=File(dir,"${it.id}.pdf").also { target -> File(it.path).copyTo(target,overwrite=true) }.path) }
        val a=AtomicFile(File(dir,"results.json")); val out=a.startWrite()
        try { out.write(JSONArray(ready.map { JSONObject().put("id",it.id).put("document",it.documentId).put("title",it.title).put("path",it.path).put("count",it.pageCount).put("created",it.createdAt).put("protected",it.protected) }).toString().toByteArray()); a.finishWrite(out) } catch(t:Throwable) { a.failWrite(out); throw t }
    }
    suspend fun cleanTemporaryResults()=withContext(Dispatchers.IO) { File(context.cacheDir,"shared-pdfs").listFiles().orEmpty().filter { System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }.forEach { it.deleteRecursively() } }

    suspend fun execute(id: String, progress: suspend (Int, Int) -> Unit) = processing.withLock { withContext(Dispatchers.IO) {
        if (File(folder(id), "complete").exists()) return@withContext
        if (results(id).isNotEmpty()) return@withContext
        val request = request(id); val document = request.getString("document"); val dir = folder(id)
        val owner=documents.dao.document(document)
        require(owner!=null && !owner.deleting && owner.trashedAt==null) { "Restore this document from Recycle Bin first." }
        request.optJSONArray("sourceDocuments")?.let { sources -> repeat(sources.length()) { index ->
            val source=documents.dao.document(sources.getString(index))
            require(source!=null && !source.deleting && source.trashedAt==null) { "Restore the selected documents before retrying." }
        } }
        val outputs = File(dir, "outputs").apply { deleteRecursively(); mkdirs() }
        val kind = request.getString("kind"); val title = request.getString("title"); val password = request.getString("password")
        val sourcePassword = request.getString("sourcePassword")
        val sources = request.optJSONArray("sources")?.let { array -> (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            fun ints(key: String) = item.getJSONArray(key).let { values -> (0 until values.length()).map { values.getInt(it) } }
            PdfSource(File(item.getString("file")), ints("pages"), ints("rotations"), sourcePassword)
        } }.orEmpty()
        val assets = mutableListOf<PdfAsset>()
        val job = currentCoroutineContext()
        fun report(done: Int, total: Int) { job.ensureActive(); runBlocking { progress(done, total) } }
        fun asset(file: File, suffix: String = "", count: Int) { assets += PdfAsset(UUID.nameUUIDFromBytes("$id-${assets.size}".toByteArray()).toString(), document, id, title + suffix, file.path, count, System.currentTimeMillis(), password.isNotEmpty()) }
        try {
            when (kind) {
                "generate" -> {
                    val files = request.getJSONArray("images").let { array -> (0 until array.length()).map { File(array.getString(it)) } }
                    val file = File(outputs, "document.pdf"); engine.generate(files, file, title, PdfQuality.valueOf(request.getString("quality")), password, ::report, request.optJSONArray("layouts")?.let { a -> List(a.length()) { i -> val p=a.getJSONObject(i); PageLayout(p.getString("size"),p.getString("fit"),p.getDouble("width"),p.getDouble("height")) } }.orEmpty()); asset(file, count = files.size)
                }
                "combine" -> { val file = File(outputs, "document.pdf"); engine.combine(sources, file, title, password, PdfQuality.valueOf(request.getString("quality")), ::report); asset(file, count = sources.sumOf { it.pages.size }) }
                "split" -> {
                    val source = sources.single(); val size = request.getInt("splitSize"); require(size in 1..500)
                    val chunks = source.pages.chunked(size)
                    chunks.forEachIndexed { index, pages ->
                        report(index, chunks.size); val file = File(outputs, "part-$index.pdf")
                        engine.combine(listOf(source.copy(pages = pages, rotations = List(pages.size) { 0 })), file, "$title ${index + 1}", password) { _, _ -> job.ensureActive() }
                        asset(file, " ${index + 1}", pages.size)
                    }; report(chunks.size, chunks.size)
                }
                "import" -> {
                    val source = sources.single()
                    source.pages.forEachIndexed { index, page ->
                        report(index, source.pages.size)
                        val pageId = UUID.nameUUIDFromBytes("$id-page-$page".toByteArray()).toString()
                        if (documents.dao.page(pageId) == null) {
                            val bitmap = engine.render(source.file, page, outputs, source.password, 3000)
                            val image = File(outputs,"page.jpg")
                            try { image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it)) } } finally { bitmap.recycle() }
                            image.inputStream().use { documents.importImage(document, it, pageId, detectDocument = false) }
                        }
                    }
                    report(source.pages.size, source.pages.size)
                }
                "export" -> {
                    val file = sources.single().file; val target = Uri.parse(request.getString("destination"))
                    context.contentResolver.openOutputStream(target, "rwt")!!.use { out -> file.inputStream().use { input ->
                        val buffer = ByteArray(65536); var done = 0L
                        while (true) { job.ensureActive(); val size = input.read(buffer); if (size < 0) break
                            out.write(buffer, 0, size); done += size
                            if (done % (1024 * 1024) < buffer.size) report((done / 1024).toInt(), (file.length() / 1024).toInt().coerceAtLeast(1))
                        }; out.flush(); report((file.length() / 1024).toInt().coerceAtLeast(1), (file.length() / 1024).toInt().coerceAtLeast(1))
                    } }
                }
            }
            ensureActive()
            if (assets.isNotEmpty()) publishResults(id, assets)
            val marker = AtomicFile(File(dir, "complete")); val out = marker.startWrite()
            try { out.write(1); marker.finishWrite(out) } catch (failure: Exception) { marker.failWrite(out); throw failure }
        } finally { withContext(NonCancellable) { outputs.deleteRecursively() } }
    } }
}

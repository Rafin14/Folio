package dev.folio.scanner.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.scanner.data.DocumentRepository
import dev.folio.scanner.data.PageLayout
import dev.folio.scanner.pdfanalysis.retryAnalysisExport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Temporary utility sessions and resumable exports, never Folio documents or Room pages. */
@Singleton
class PdfUtility @Inject constructor(@ApplicationContext val context:Context,val engine:PdfEngine,val documents:DocumentRepository,val pipeline:dev.folio.scanner.processing.ImagePipeline) {
    private val processing=Mutex()
    private val resourceLocks=Array(32) {Mutex()}
    suspend fun <T> withSessionResources(id:String,action:suspend ()->T):T =
        resourceLocks[(id.hashCode() and Int.MAX_VALUE)%resourceLocks.size].withLock {action()}
    @Synchronized fun requireActiveSession(id:String) {
        if(session(id).optString("state")=="discarding") throw CancellationException("This PDF operation was discarded.")
    }
    val work get()=WorkManager.getInstance(context)
    fun folder(id:String,create:Boolean=true):File { require(UUID.fromString(id).toString()==id); return File(context.filesDir,"pdf-utility/$id").apply { if(create) mkdirs() } }
    // AtomicFile readers can discard an in-progress .new file; readers and writers must share a lock.
    @Synchronized fun save(id:String,j:JSONObject) { val existing=File(folder(id,create=false),"session.json"); if(existing.isFile && session(id).optString("state")=="discarding" && j.optString("state")!="discarding") throw CancellationException("This PDF operation was discarded."); val a=AtomicFile(File(folder(id),"session.json")); val out=a.startWrite(); try { out.write(j.toString().toByteArray()); a.finishWrite(out) } catch(t:Throwable) { a.failWrite(out); throw t } }
    @Synchronized fun session(id:String):JSONObject {
        require(UUID.fromString(id).toString()==id)
        // Reads must not resurrect a discarded session during WorkManager/UI updates.
        return JSONObject(String(AtomicFile(File(context.filesDir,"pdf-utility/$id/session.json")).readFully()))
    }
    fun pending():List<String> = File(context.filesDir,"pdf-utility").listFiles().orEmpty().filter { File(it,"session.json").isFile }.map { it.name }.filter { session(it).optString("state")!="complete" }
    fun name(uri:Uri):String = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Document"
    fun grant(uri:Uri,write:Boolean=false) { runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) } }
    suspend fun copy(uri:Uri,target:File)=withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> val buffer=ByteArray(65536); var total=0L; while(true) { ensureActive(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=500L*1024*1024) { "Choose files smaller than 500 MB." }; out.write(buffer,0,n) } } } ?: error("Could not read selected file.")
    }
    suspend fun open(kind:String,uris:List<Uri>,password:String="",initialImport:Boolean=false):String=withContext(Dispatchers.IO) {
        require(uris.size in 1..50)
        val id=UUID.randomUUID().toString(); val dir=folder(id)
        try {
            val names=uris.map { name(it) }
            uris.forEachIndexed { i,u -> grant(u); copy(u,File(dir,"input-$i")) }
            val counts=if(kind=="images") emptyList() else uris.indices.map { engine.count(File(dir,"input-$it"),password) }
            if(kind!="images") uris.indices.forEach { i -> engine.read(File(dir,"input-$i"),password).use { require(it.reader.isOpenedWithFullPermission) { "Use this PDF's owner password to edit or copy it." } } }
            if(initialImport) File(dir,"input-0").copyTo(File(dir,"import-original.pdf"))
            // Normalize protected inputs to an unprotected temporary copy; no password is persisted.
            if(kind!="images") uris.indices.forEach { i -> val input=File(dir,"input-$i"); val normalized=File(dir,"normalized.pdf"); engine.combine(listOf(PdfSource(input,(1..counts[i]).toList(),password=password)),normalized,names[i]) { _,_ -> ensureActive() }; check(input.delete() && normalized.renameTo(input)) }
            val pages=if(kind=="edit") JSONArray(List(counts.single()) { UtilityPage(source=it+1).json() }) else JSONArray()
            save(id,JSONObject().apply {if(initialImport) put("initialImportDocument",UUID.randomUUID().toString()).put("initialImportProtected",password.isNotEmpty())}.put("kind",kind).put("names",JSONArray(names)).put("counts",JSONArray(counts)).put("order",JSONArray(uris.indices.toList())).put("pages",pages).put("dirty",false).put("state","editing").put("quality",PdfQuality.ORIGINAL.name))
            id
        } catch(t:Throwable) { withContext(NonCancellable) { dir.deleteRecursively() }; throw t }
    }
    /** Explicit save reserves one durable identity; recovery resumes only requested publication. */
    suspend fun persistInitialImport(id:String):String=withContext(Dispatchers.IO) {
        val current=session(id)
        if(!current.optBoolean("initialImportSaved")) save(id,current.put("initialImportSaveRequested",true))
        if(current.optBoolean("initialImportSaved") && current.optBoolean("dirty"))
            return@withContext saveToFolio(id,pdfFileStem(current.getJSONArray("names").getString(0)),overwrite=true)
        processing.withLock {
        val j=session(id);val document=j.getString("initialImportDocument")
        check(!j.optBoolean("initialImportSaved") || documents.dao.document(document)!=null) {"The imported document was removed from Folio."}
        if(documents.dao.document(document)==null) {
            val title=pdfFileStem(j.getJSONArray("names").getString(0))
            val edited=j.optBoolean("dirty");val output=File(folder(id,create=false),"import-result.pdf")
            try {
                val source=if(edited) output.also {val job=currentCoroutineContext();engine.editUtility(File(folder(id,create=false),"input-0"),pages(id),it,title) {_,_->job.ensureActive()}}
                    else File(folder(id,create=false),if(j.optBoolean("initialImportProtected")) "input-0" else "import-original.pdf")
                documents.importPdf(source,title,engine,newDocumentId=document)
            } finally {output.delete()}
        }
        val stored=requireNotNull(documents.dao.document(document)) {"PDF import was not saved."}
        save(id,j.put("folioDocument",document).put("folioHash",stored.pdfHash).put("folioModifiedAt",stored.modifiedAt).put("dirty",false).put("initialImportSaved",true))
        document
    }}
    private suspend fun <T> withFolioInput(documentId:String,action:suspend (Uri)->T):T=withContext(Dispatchers.IO) {
        val dir=File(context.cacheDir,"shared-pdfs/utility-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val doc=requireNotNull(documents.dao.document(documentId)) { "Document no longer exists." }
            require(!doc.deleting && doc.trashedAt==null && doc.pageCount>0) { "Choose a document with available pages." }
            val input=File(dir,"input.pdf")
            if(doc.importedPdf) documents.snapshotPdf(documentId,input,engine) else {
                val layouts=mutableListOf<PageLayout>()
                val images=documents.snapshotImages(documentId,File(dir,"images"),layouts=layouts)
                val job=currentCoroutineContext()
                engine.generate(images,input,doc.title,progress={ _,_ -> job.ensureActive() },layouts=layouts)
            }
            action(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",input,pdfFileStem(doc.title)+".pdf"))
        } finally { withContext(NonCancellable) { dir.deleteRecursively() } }
    }
    suspend fun openFolioInput(kind:String,documentId:String):String {
        require(kind in listOf("split","merge","raster"))
        return withFolioInput(documentId) { open(kind,listOf(it)) }
    }
    suspend fun addFolioMergeInput(id:String,documentId:String)=withFolioInput(documentId) { addMergeInputs(id,listOf(it)) }
    suspend fun fromDocuments(ids:List<String>):String=fromPages(ids.flatMap { doc -> documents.dao.pages(doc).map { FolioPageChoice(doc,it.id) } })
    suspend fun openManaged(documentId:String):String=withContext(Dispatchers.IO) {
        val id=UUID.randomUUID().toString(); val input=File(folder(id),"input-0")
        try {
            val doc=documents.snapshotPdf(documentId,input,engine)
            val count=engine.count(input)
            save(id,JSONObject().put("kind","edit").put("names",JSONArray(listOf(doc.title)))
                .put("counts",JSONArray(listOf(count))).put("pages",JSONArray(List(count) { UtilityPage(source=it+1).json() }))
                .put("order",JSONArray(listOf(0))).put("dirty",false).put("state","editing").put("quality",PdfQuality.ORIGINAL.name)
                .put("folioDocument",documentId).put("folioHash",doc.pdfHash).put("folioModifiedAt",doc.modifiedAt))
            id
        } catch(t:Throwable) { folder(id).deleteRecursively(); throw t }
    }
    suspend fun saveToFolio(id:String,title:String,overwrite:Boolean=false):String=processing.withLock { withContext(Dispatchers.IO) {
        val j=session(id); require(j.getString("kind")=="edit")
        val output=File(folder(id),"folio-output.pdf")
        try {
            val job=currentCoroutineContext()
            engine.editUtility(File(folder(id),"input-0"),pages(id),output,title) { _,_ -> job.ensureActive() }
            engine.count(output)
            val document=documents.importPdf(output,title,engine,
                if(overwrite) j.getString("folioDocument") else null,if(overwrite) j.getString("folioHash") else null,if(overwrite) j.getLong("folioModifiedAt") else null)
            // Keep a valid editable temporary session so storage export remains available.
            if(overwrite || !j.has("folioDocument")) {
                val stored=documents.dao.document(document)!!
                j.put("folioDocument",document).put("folioHash",stored.pdfHash).put("folioModifiedAt",stored.modifiedAt)
            }
            j.put("dirty",false)
            save(id,j); document
        } finally { output.delete() }
    } }
    suspend fun fromPages(selection:List<FolioPageChoice>):String=withContext(Dispatchers.IO) {
        require(selection.size in 1..500 && selection.map { it.pageId }.distinct().size==selection.size) { "Select 1 to 500 different pages." }
        val ids=selection.map { it.documentId }.distinct(); require(ids.size<=50)
        val id=UUID.randomUUID().toString(); val layouts=mutableListOf<PageLayout>()
        try {
            val names=ids.map { requireNotNull(documents.dao.document(it)).title }
            val snapshots=ids.flatMapIndexed { i,doc ->
                val chosen=selection.filter { it.documentId==doc }; val papers=mutableListOf<PageLayout>()
                val images=documents.snapshotImages(doc,File(folder(id),"images/$i"),layouts=papers,ordered=chosen.map { it.pageId })
                chosen.indices.map { n -> chosen[n] to (images[n] to papers[n]) }
            }.toMap()
            val files=selection.map { snapshots.getValue(it).first }; layouts.addAll(selection.map { snapshots.getValue(it).second })
            save(id,JSONObject().put("kind","generate").put("names",JSONArray(names)).put("images",JSONArray(files.map { it.path })).put("layouts",JSONArray(layouts.map { JSONObject().put("size",it.size).put("fit",it.fit).put("width",it.widthMm).put("height",it.heightMm) })).put("state","editing").put("quality",PdfQuality.ORIGINAL.name))
            id
        } catch(t:Throwable) { withContext(NonCancellable) { folder(id).deleteRecursively() }; throw t }
    }
    suspend fun addMergeInputs(id:String,uris:List<Uri>,password:String="")=withContext(Dispatchers.IO) {
        require(work.getWorkInfosByTagFlow("utility-$id").first().all { it.state.isFinished })
        val j=session(id); require(j.getString("kind")=="merge" && j.optString("state")!="complete")
        val names=j.getJSONArray("names"); val counts=j.getJSONArray("counts"); val order=j.getJSONArray("order")
        require(names.length()+uris.size<=50) { "Choose at most 50 PDFs." }
        // Prepare all additions before atomically publishing the changed merge list.
        val added=mutableListOf<File>()
        try { uris.forEach { uri ->
            val index=names.length(); val file=File(folder(id),"input-$index"); added+=file; copy(uri,file)
            val count=engine.count(file,password)
            engine.read(file,password).use { require(it.reader.isOpenedWithFullPermission) { "Use the PDF's owner password." } }
            val normalized=File(folder(id),"normalized-$index.pdf"); added+=normalized
            engine.combine(listOf(PdfSource(file,(1..count).toList(),password=password)),normalized,name(uri)) { _,_ -> ensureActive() }
            check(file.delete() && normalized.renameTo(file)); names.put(name(uri)); counts.put(count); order.put(index)
        }; save(id,j.put("state","editing")) } catch(t:Throwable) { added.forEach { it.delete() }; throw t }
    }
    fun removeMergeInput(id:String,index:Int) {
        val j=session(id); val order=j.getJSONArray("order").let { a -> List(a.length()) { a.getInt(it) } }
        require(index in order) { "PDF is no longer selected." }
        save(id,j.put("order",JSONArray(order-index)).put("state","editing"))
    }
    private val destinations get()=context.getSharedPreferences("pdf-utility-destinations",Context.MODE_PRIVATE)
    fun rememberDestination(type:String,uri:Uri):Boolean {
        grant(uri,true)
        val persisted=context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission && it.isWritePermission }
        if(persisted) destinations.edit().putString(type,uri.toString()).commit()
        return persisted
    }
    fun rememberedDestination(type:String):Uri? {
        val raw=destinations.getString(type,null) ?: return null
        val uri=Uri.parse(raw)
        val valid=runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri==uri && it.isReadPermission && it.isWritePermission } &&
                context.contentResolver.query(DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri)),arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use {
                    it.moveToFirst() && it.getString(0)==DocumentsContract.Document.MIME_TYPE_DIR && it.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE!=0
                }==true
        }.getOrDefault(false)
        if(!valid) destinations.edit().remove(type).commit()
        return uri.takeIf { valid }
    }
    suspend fun destinationFile(folder:Uri,title:String):Uri=withContext(Dispatchers.IO) {
        requireNotNull(DocumentsContract.createDocument(context.contentResolver,DocumentsContract.buildDocumentUriUsingTree(folder,DocumentsContract.getTreeDocumentId(folder)),"application/pdf",pdfFileStem(title)+".pdf")) { "Choose another writable folder." }
    }
    fun pages(id:String)=session(id).getJSONArray("pages").let { a -> List(a.length()) { UtilityPage.from(a.getJSONObject(it)) } }
    /** Shared SAF publication: stream output and verify through the selected provider. */
    fun publishOutput(file:File,uri:Uri,cancelled:()->Unit={}) {
        cancelled()
        // Once SAF truncates the destination, finish and verify this file before cancellation.
        context.contentResolver.openOutputStream(uri,"rwt")!!.use { out -> file.inputStream().use { input ->
            val buffer=ByteArray(65536);while(true) {val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)};out.flush()
        } }
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        fun hash(input:java.io.InputStream):ByteArray {digest.reset();input.use {val b=ByteArray(65536);while(true) {val n=it.read(b);if(n<0) break;digest.update(b,0,n)}};return digest.digest()}
        check(hash(file.inputStream()).contentEquals(hash(context.contentResolver.openInputStream(uri)!!))) { "Export verification failed. Retry." }
    }
    @Synchronized fun updatePages(id:String,pages:List<UtilityPage>) { require(pages.isNotEmpty()); save(id,session(id).put("pages",JSONArray(pages.map { it.json() })).put("dirty",true)) }
    suspend fun preview(id:String,page:UtilityPage,edge:Int=1400,includeAnnotations:Boolean=false):Bitmap=withSessionResources(id) { requireActiveSession(id);renderPreview(id,page,edge,includeAnnotations) }
    private suspend fun renderPreview(id:String,page:UtilityPage,edge:Int,includeAnnotations:Boolean):Bitmap=withContext(Dispatchers.IO) {
        val dir=folder(id,create=false); val file=File.createTempFile("view-",".pdf",dir)
        try { engine.editUtility(File(dir,"input-0"),listOf(if(includeAnnotations) page else page.copy(ink=emptyList(),notes=emptyList())),file,"Preview"); engine.render(file,1,dir,edge=edge) } finally { file.delete() }
    }
    suspend fun enqueue(id:String,target:Uri,tree:Boolean,title:String,selection:String="")=withContext(Dispatchers.IO) { synchronized(this@PdfUtility) {
        requireActiveSession(id)
        require(work.getWorkInfosByTag("utility-$id").get().all { it.state.isFinished }) { "Export is already running." }
        grant(target,true)
        val request=OneTimeWorkRequestBuilder<PdfUtilityWorker>().setInputData(workDataOf("session" to id)).addTag("pdf-utility").addTag("utility-$id").build()
        val j=session(id)
        if(j.has("destination") && j.getString("destination")!=target.toString()) {
            // A new folder needs all outputs. Leave verified files at the old destination untouched.
            j.remove("outputFolder"); j.remove("outputs"); j.remove("completed")
        }
        j.put("destination",target.toString()).put("tree",tree).put("title",pdfFileStem(title)).put("selection",selection).put("state","pending").put("workId",request.id.toString())
        save(id,j)
        work.enqueueUniqueWork("utility-$id",ExistingWorkPolicy.KEEP,request).result.get()
        File(folder(id),"scheduled").writeText("1")
    }}
    suspend fun recover()=withContext(Dispatchers.IO) {
        pending().forEach { id ->
            val j=session(id)
            if(j.optString("state")=="discarding") {cancelAndDiscard(id);return@forEach}
            if(j.has("initialImportDocument") && j.optBoolean("initialImportSaveRequested") && !j.optBoolean("initialImportSaved")) persistInitialImport(id)
            if(j.optString("kind")=="analysis") {
                val pending=File(folder(id),"analysis-export.json")
                if(pending.isFile) {
                    val request=JSONObject(pending.readText())
                    if(request.optString("state")=="pending" && work.getWorkInfoById(UUID.fromString(request.getString("work"))).get()==null) retryAnalysisExport(id)
                }
            }
            val infos=work.getWorkInfosByTagFlow("utility-$id").first()
            if(j.optString("state")=="pending" && infos.isEmpty()) {
                if(!File(folder(id),"scheduled").exists()) enqueue(id,Uri.parse(j.getString("destination")),j.getBoolean("tree"),j.getString("title"),j.optString("selection"))
                else save(id,j.put("state","interrupted"))
            }
        }
        File(context.filesDir,"pdf-utility").listFiles().orEmpty().filter { File(it,"session.json").isFile && session(it.name).optString("state")=="complete" && System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }.forEach { it.deleteRecursively() }
    }
    /** Cancel only this temporary session, await its workers, then remove its private assets. */
    suspend fun cancelAndDiscard(id:String)=withContext(Dispatchers.IO) {
        synchronized(this@PdfUtility) {if(!File(folder(id,create=false),"session.json").exists()) return@withContext;save(id,session(id).put("state","discarding"))}
        val tags=listOf("utility-$id","analysis-$id","analysis-export-$id")
        tags.forEach { work.cancelAllWorkByTag(it).result.get() }
        withTimeout(30000) { tags.forEach { tag -> work.getWorkInfosByTagFlow(tag).first { infos -> infos.all { it.state.isFinished } } } }
        // Finished WorkInfo can precede CoroutineWorker/native finally blocks.
        withSessionResources(id) { discard(id) }
    }
    suspend fun discard(id:String)=withContext(Dispatchers.IO) { require(listOf("utility-$id","analysis-$id","analysis-export-$id").all { tag -> work.getWorkInfosByTagFlow(tag).first().all { it.state.isFinished } }) { "Cancel processing first." }; folder(id,create=false).deleteRecursively() }
    suspend fun execute(id:String,progress:suspend(Int,Int)->Unit)=withSessionResources(id) { requireActiveSession(id);executeUnlocked(id,progress) }
    private suspend fun executeUnlocked(id:String,progress:suspend(Int,Int)->Unit)=processing.withLock { withContext(Dispatchers.IO) {
        val j=session(id); if(j.optString("state")=="complete") return@withContext
        val dir=folder(id); val output=File(dir,"output.pdf"); val kind=j.getString("kind"); val title=j.getString("title"); val destination=Uri.parse(j.getString("destination")); val job=currentCoroutineContext()
        fun report(done:Int,total:Int) { job.ensureActive(); runBlocking(job) { progress(done,total) } }
        fun publish(file:File,uri:Uri)=publishOutput(file,uri) { job.ensureActive() }
        val quality=PdfQuality.valueOf(j.getString("quality"))
        if(kind=="split" || kind=="raster") {
            val stem=pdfFileStem(j.getJSONArray("names").getString(0)); val parent=DocumentsContract.buildDocumentUriUsingTree(destination,DocumentsContract.getTreeDocumentId(destination))
            val folderUri=if(j.has("outputFolder")) Uri.parse(j.getString("outputFolder")) else requireNotNull(DocumentsContract.createDocument(context.contentResolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,stem)).also { j.put("outputFolder",it.toString()); save(id,j) }
            val count=j.getJSONArray("counts").getInt(0)
            val groups=if(kind=="split") splitGroups(j.getString("selection"),count) else pageSelection(j.getString("selection"),count).map { listOf(it) }
            val completed=j.optJSONArray("completed") ?: JSONArray().also { j.put("completed",it) }
            val outputs=j.optJSONObject("outputs") ?: JSONObject().also { j.put("outputs",it) }
            groups.forEachIndexed { index,pages ->
                report(index,groups.size)
                if((0 until completed.length()).any { completed.getInt(it)==index }) return@forEachIndexed
                val filename=if(kind=="split") splitFilename(stem,pages) else "$stem Page ${pages.single()}.png"
                if(kind=="split") engine.combine(listOf(PdfSource(File(dir,"input-0"),pages)),output,title,quality=quality) { _,_ -> job.ensureActive() }
                else { val bitmap=engine.render(File(dir,"input-0"),pages.single(),dir,edge=3000); try { output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() } }
                val uri=if(outputs.has(index.toString())) Uri.parse(outputs.getString(index.toString())) else requireNotNull(DocumentsContract.createDocument(context.contentResolver,folderUri,if(kind=="split") "application/pdf" else "image/png",filename)).also { outputs.put(index.toString(),it.toString()); save(id,j) }
                publish(output,uri); completed.put(index); save(id,j)
            }; report(groups.size,groups.size)
        } else {
            when(kind) {
                "merge" -> { val order=j.getJSONArray("order"); engine.combine(List(order.length()) { i -> val n=order.getInt(i); PdfSource(File(dir,"input-$n"),(1..j.getJSONArray("counts").getInt(n)).toList()) },output,title,quality=quality,progress=::report) }
                "images" -> {
                    val files=List(j.getJSONArray("names").length()) { i -> val bitmap=pipeline.decode(File(dir,"input-$i")); try { File(dir,"image-$i.jpg").also { f -> f.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) } } } finally { bitmap.recycle() } }
                    engine.generate(files,output,title,quality,progress=::report)
                }
                "generate" -> { val a=j.getJSONArray("images"); val l=j.getJSONArray("layouts"); engine.generate(List(a.length()) { File(a.getString(it)) },output,title,quality,progress=::report,layouts=List(l.length()) { val p=l.getJSONObject(it); PageLayout(p.getString("size"),p.getString("fit"),p.getDouble("width"),p.getDouble("height")) }) }
                "edit" -> engine.editUtility(File(dir,"input-0"),pages(id),output,title,::report)
                else -> error("Unknown PDF operation.")
            }
            publish(output,destination)
        }
        job.ensureActive(); save(id,JSONObject().put("kind",kind).put("state","complete").put("dirty",false).put("destination",destination.toString()).put("outputFolder",j.optString("outputFolder"))); output.delete()
        // Inputs are temporary, not a saved-PDF collection. Completed exports leave only a small receipt.
        dir.listFiles().orEmpty().filter { it.name!="session.json" }.forEach { it.deleteRecursively() }
    } }
}

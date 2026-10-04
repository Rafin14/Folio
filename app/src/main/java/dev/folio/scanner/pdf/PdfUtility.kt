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
    val work get()=WorkManager.getInstance(context)
    fun folder(id:String):File { require(UUID.fromString(id).toString()==id); return File(context.filesDir,"pdf-utility/$id").apply { mkdirs() } }
    fun save(id:String,j:JSONObject) { val a=AtomicFile(File(folder(id),"session.json")); val out=a.startWrite(); try { out.write(j.toString().toByteArray()); a.finishWrite(out) } catch(t:Throwable) { a.failWrite(out); throw t } }
    fun session(id:String)=JSONObject(String(AtomicFile(File(folder(id),"session.json")).readFully()))
    fun pending():List<String> = File(context.filesDir,"pdf-utility").listFiles().orEmpty().filter { File(it,"session.json").isFile }.map { it.name }.filter { session(it).optString("state")!="complete" }
    fun name(uri:Uri):String = context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "Document"
    fun grant(uri:Uri,write:Boolean=false) { runCatching { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or if(write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) } }
    suspend fun copy(uri:Uri,target:File)=withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> val buffer=ByteArray(65536); var total=0L; while(true) { ensureActive(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=500L*1024*1024) { "Choose files smaller than 500 MB." }; out.write(buffer,0,n) } } } ?: error("Could not read selected file.")
    }
    suspend fun open(kind:String,uris:List<Uri>,password:String=""):String=withContext(Dispatchers.IO) {
        require(uris.size in 1..50)
        val id=UUID.randomUUID().toString(); val dir=folder(id)
        try {
            val names=uris.map { name(it) }
            uris.forEachIndexed { i,u -> grant(u); copy(u,File(dir,"input-$i")) }
            val counts=if(kind=="images") emptyList() else uris.indices.map { engine.count(File(dir,"input-$it"),password) }
            if(kind!="images") uris.indices.forEach { i -> engine.read(File(dir,"input-$i"),password).use { require(it.reader.isOpenedWithFullPermission) { "Use this PDF's owner password to edit or copy it." } } }
            // Normalize protected inputs to an unprotected temporary copy; no password is persisted.
            if(kind!="images") uris.indices.forEach { i -> val input=File(dir,"input-$i"); val normalized=File(dir,"normalized.pdf"); engine.combine(listOf(PdfSource(input,(1..counts[i]).toList(),password=password)),normalized,names[i]) { _,_ -> ensureActive() }; check(input.delete() && normalized.renameTo(input)) }
            val pages=if(kind=="edit") JSONArray(List(counts.single()) { UtilityPage(source=it+1).json() }) else JSONArray()
            save(id,JSONObject().put("kind",kind).put("names",JSONArray(names)).put("counts",JSONArray(counts)).put("order",JSONArray(uris.indices.toList())).put("pages",pages).put("dirty",false).put("state","editing").put("quality",PdfQuality.ORIGINAL.name))
            id
        } catch(t:Throwable) { withContext(NonCancellable) { dir.deleteRecursively() }; throw t }
    }
    suspend fun fromDocuments(ids:List<String>):String=fromPages(ids.flatMap { doc -> documents.dao.pages(doc).map { FolioPageChoice(doc,it.id) } })
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
    fun updatePages(id:String,pages:List<UtilityPage>) { require(pages.isNotEmpty()); save(id,session(id).put("pages",JSONArray(pages.map { it.json() })).put("dirty",true)) }
    suspend fun preview(id:String,page:UtilityPage,edge:Int=1400):Bitmap=withContext(Dispatchers.IO) {
        val dir=folder(id); val file=File.createTempFile("view-",".pdf",dir)
        try { engine.editUtility(File(dir,"input-0"),listOf(page.copy(ink=emptyList(),notes=emptyList())),file,"Preview"); engine.render(file,1,dir,edge=edge) } finally { file.delete() }
    }
    suspend fun enqueue(id:String,target:Uri,tree:Boolean,title:String,selection:String="")=withContext(Dispatchers.IO) {
        require(work.getWorkInfosByTagFlow("utility-$id").first().all { it.state.isFinished }) { "Export is already running." }
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
    }
    suspend fun recover()=withContext(Dispatchers.IO) {
        pending().forEach { id ->
            val j=session(id)
            val infos=work.getWorkInfosByTagFlow("utility-$id").first()
            if(j.optString("state")=="pending" && infos.isEmpty()) {
                if(!File(folder(id),"scheduled").exists()) enqueue(id,Uri.parse(j.getString("destination")),j.getBoolean("tree"),j.getString("title"),j.optString("selection"))
                else save(id,j.put("state","interrupted"))
            }
        }
        File(context.filesDir,"pdf-utility").listFiles().orEmpty().filter { File(it,"session.json").isFile && session(it.name).optString("state")=="complete" && System.currentTimeMillis()-it.lastModified()>24*60*60*1000L }.forEach { it.deleteRecursively() }
    }
    suspend fun discard(id:String)=withContext(Dispatchers.IO) { require(work.getWorkInfosByTagFlow("utility-$id").first().all { it.state.isFinished }) { "Cancel export first." }; folder(id).deleteRecursively() }
    suspend fun execute(id:String,progress:suspend(Int,Int)->Unit)=processing.withLock { withContext(Dispatchers.IO) {
        val j=session(id); if(j.optString("state")=="complete") return@withContext
        val dir=folder(id); val output=File(dir,"output.pdf"); val kind=j.getString("kind"); val title=j.getString("title"); val destination=Uri.parse(j.getString("destination")); val job=currentCoroutineContext()
        fun report(done:Int,total:Int) { job.ensureActive(); runBlocking { progress(done,total) } }
        fun publish(file:File,uri:Uri) {
            context.contentResolver.openOutputStream(uri,"rwt")!!.use { out -> file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { job.ensureActive(); val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; out.flush() } }
            // Verify bytes through the storage provider, rather than assuming the write succeeded.
            val md=java.security.MessageDigest.getInstance("SHA-256")
            fun hash(stream:java.io.InputStream):ByteArray { md.reset(); stream.use { input -> val b=ByteArray(65536); while(true) { job.ensureActive(); val n=input.read(b); if(n<0) break; md.update(b,0,n) } }; return md.digest() }
            check(hash(file.inputStream()).contentEquals(hash(context.contentResolver.openInputStream(uri)!!))) { "Export verification failed. Retry." }
        }
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

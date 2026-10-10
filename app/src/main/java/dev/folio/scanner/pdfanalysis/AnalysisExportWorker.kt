package dev.folio.scanner.pdfanalysis

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

fun PdfUtility.exportAnalysis(session:String,destination:Uri,type:String,extra:JSONObject=JSONObject()):UUID=synchronized(this) {
    requireActiveSession(session)
    require(work.getWorkInfosByTag("analysis-export-$session").get().all {it.state.isFinished}) {"An export is already running."}
    grant(destination,true)
    val job=OneTimeWorkRequestBuilder<AnalysisExportWorker>().setInputData(workDataOf("session" to session)).addTag("analysis-export-$session").build()
    dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(File(folder(session),"analysis-export.json"),extra.put("destination",destination.toString()).put("type",type).put("state","pending").put("work",job.id.toString()).toString().toByteArray())
    work.enqueueUniqueWork("folio-pdf-analysis-export",ExistingWorkPolicy.APPEND_OR_REPLACE,job).result.get()
    job.id
}
fun PdfUtility.retryAnalysisExport(session:String):UUID {
    val request=JSONObject(File(folder(session),"analysis-export.json").readText())
    return exportAnalysis(session,Uri.parse(request.getString("destination")),request.getString("type"),request)
}

class AnalysisExportWorker(context:Context,private val params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo()=PdfUtilityWorker(applicationContext,params).getForegroundInfo()
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val session=inputData.getString("session") ?: return@withContext Result.failure()
        val utility=EntryPointAccessors.fromApplication(applicationContext,PdfWorkerDependencies::class.java).utility()
        utility.withSessionResources(session) {runSession(session,utility)}
    }
    private suspend fun runSession(session:String,utility:PdfUtility):Result=withContext(Dispatchers.IO) {
        utility.requireActiveSession(session)
        val folder=utility.folder(session,create=false);val temporary=File(folder,"analysis-export.writing")
        try {
            setForeground(getForegroundInfo())
            val request=JSONObject(File(folder,"analysis-export.json").readText());val count=utility.session(session).getJSONArray("counts").getInt(0)
            val job=currentCoroutineContext()
            fun checkpoint()=dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(File(folder,"analysis-export.json"),request.toString().toByteArray())
            when(request.getString("type")) {
                "text" -> temporary.bufferedWriter().use {out ->for(index in 0 until count) {
                    ensureActive();setProgress(workDataOf("done" to index,"total" to count))
                    val page=AnalysisPage.parse(JSONObject(ocrResultFile(folder,index).readText()));out.append(page.text)
                    if(index<count-1) out.append("\n\n\u000c\n\n")
                }}
                "figures" -> {
                    val tree=Uri.parse(request.getString("destination"));val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                    val destination=if(request.has("outputFolder")) Uri.parse(request.getString("outputFolder")) else requireNotNull(DocumentsContract.createDocument(applicationContext.contentResolver,parent,DocumentsContract.Document.MIME_TYPE_DIR,pdfFileStem(utility.session(session).getJSONArray("names").getString(0)))).also {request.put("outputFolder",it.toString());checkpoint()}
                    val figures=request.getJSONArray("figures");val outputs=request.optJSONObject("outputs") ?: JSONObject().also {request.put("outputs",it)}
                    val completed=request.optJSONArray("completed") ?: org.json.JSONArray().also {request.put("completed",it)}
                    PdfiumDocument(File(folder,"input-0")).use {pdf ->repeat(figures.length()) {index ->
                        ensureActive();setProgress(workDataOf("done" to index,"total" to figures.length()))
                        if((0 until completed.length()).none {completed.getInt(it)==index}) {
                            val figure=Figure.parse(figures.getJSONObject(index));val page=AnalysisPage.parse(JSONObject(File(folder,"analysis-${figure.page}.json").readText()))
                            val output=extractFigure(pdf,page,figure,folder) {job.ensureActive()}
                            try {
                                val name="${pdfFileStem(utility.session(session).getJSONArray("names").getString(0))} Page ${figure.page+1} Figure ${index+1}.${output.format.extension}"
                                val uri=if(outputs.has(index.toString())) Uri.parse(outputs.getString(index.toString())) else requireNotNull(DocumentsContract.createDocument(applicationContext.contentResolver,destination,output.format.mime,name)).also {outputs.put(index.toString(),it.toString());checkpoint()}
                                utility.publishOutput(output.file,uri) {job.ensureActive()};completed.put(index);checkpoint()
                            } finally {output.file.delete()}
                        }
                    }}
                    request.put("state","complete");checkpoint()
                    setProgress(workDataOf("done" to figures.length(),"total" to figures.length()))
                    return@withContext Result.success()
                }
                "word" -> {
                    val mode=WordMode.valueOf(request.getString("mode"))
                    PdfiumDocument(File(folder,"input-0")).use {pdf ->
                        val result=writeDocx(temporary,folder,count,mode,{index ->
                            job.ensureActive();val page=AnalysisPage.parse(JSONObject(File(folder,"analysis-$index.json").readText()))
                            val bitmap=android.graphics.BitmapFactory.decodeFile(File(folder,"analysis-$index.jpg").path) ?: error("Analysis page preview is missing. Retry analysis.")
                            try {reconstructWordPage(page,bitmap,pdf,folder) {job.ensureActive()}} finally {bitmap.recycle()}
                        },{job.ensureActive()},{done,total ->runBlocking(job) {setProgress(workDataOf("done" to done,"total" to total))}})
                        request.put("paragraphs",result.paragraphs).put("tables",result.tables).put("figuresCount",result.figures).put("warnings",org.json.JSONArray(result.warnings));checkpoint()
                    }
                    java.util.zip.ZipFile(temporary).use {zip ->check(zip.getEntry("word/document.xml")!=null && zip.getEntry("[Content_Types].xml")!=null)}
                }
                else -> error("Unsupported analysis export")
            }
            utility.publishOutput(temporary,Uri.parse(request.getString("destination"))) {job.ensureActive()}
            request.put("state","complete");checkpoint()
            setProgress(workDataOf("done" to count,"total" to count));Result.success()
        } catch(cancel:CancellationException) {
            withContext(NonCancellable) {runCatching {val f=File(folder,"analysis-export.json");val j=JSONObject(f.readText());dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(f,j.put("state","interrupted").toString().toByteArray())}}
            throw cancel
        }
        catch(error:Exception) {runCatching {val f=File(folder,"analysis-export.json");val j=JSONObject(f.readText());dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(f,j.put("state","failed").toString().toByteArray())};Result.failure(workDataOf("error" to (error.message?.take(240) ?: "Export failed. Retry when storage is available.")))}
        finally {temporary.delete()}
    }
}

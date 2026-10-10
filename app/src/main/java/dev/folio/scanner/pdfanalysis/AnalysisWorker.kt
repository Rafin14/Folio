package dev.folio.scanner.pdfanalysis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.folio.scanner.pdf.PdfUtility
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.UUID

internal class AnalysisConnection(private val context:Context,private val progress:(String)->Unit) {
    private val ready=CompletableDeferred<Messenger>();private var result:CompletableDeferred<Unit>?=null;private var bound=false
    private var processId=0
    private var closed=false
    private val stopped=CompletableDeferred<Unit>()
    private val replies=Messenger(Handler(Looper.getMainLooper()) { message ->
        when(message.what) {
            3 -> if(!closed) progress(message.data.getString("text").orEmpty())
            4 -> result?.complete(Unit)
            5 -> result?.completeExceptionally(IllegalStateException(message.data.getString("text")))
            7 -> stopped.complete(Unit)
            6 -> processId=message.data.getString("text")?.toIntOrNull() ?: 0
        };true
    })
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {ready.complete(Messenger(binder))}
        override fun onServiceDisconnected(name:ComponentName) {val error=IOException("Native analysis process stopped");if(!ready.isCompleted) ready.completeExceptionally(error);result?.completeExceptionally(error);stopped.complete(Unit)}
        override fun onBindingDied(name:ComponentName) {val error=IOException("Native analysis process stopped");if(!ready.isCompleted) ready.completeExceptionally(error);result?.completeExceptionally(error);stopped.complete(Unit)}
        override fun onNullBinding(name:ComponentName) {ready.completeExceptionally(IOException("PDF analysis service unavailable"))}
    }
    suspend fun start() {
        withContext(Dispatchers.Main) {bound=context.bindService(Intent(context,PdfAnalysisService::class.java),connection,Context.BIND_AUTO_CREATE);check(bound)}
        try {withTimeout(30000) {ready.await()}} catch(timeout:TimeoutCancellationException) {throw IOException("Native analysis service did not start",timeout)}
    }
    suspend fun page(folder:File,index:Int,cpu:Boolean,mode:OcrMode=OcrMode.LAYOUT_AWARE,request:String="",replaceOcr:Boolean=false) {
        val waiting=CompletableDeferred<Unit>();result=waiting
        try {
            ready.await().send(Message.obtain(null,1).apply {replyTo=replies;data=Bundle().apply {putString("folder",folder.path);putInt("page",index);putBoolean("cpu",cpu);putString("mode",mode.name);putString("request",request);putBoolean("replaceOcr",replaceOcr)}})
            try {withTimeout(240000) {waiting.await()}} catch(timeout:TimeoutCancellationException) {
                if(processId>0 && processId!=android.os.Process.myPid()) android.os.Process.killProcess(processId)
                throw IOException("Native analysis timed out; CPU retry required",timeout)
            }
        } finally {result=null}
    }
    suspend fun closeSafely()=withContext(NonCancellable) {
        if(!bound) return@withContext
        closed=true
        if(ready.isCompleted && !ready.isCancelled) {
            val target=runCatching {ready.getCompleted()}.getOrNull()
            if(target!=null) {
                try {target.send(Message.obtain(null,2).apply {replyTo=replies});stopped.await()}
                catch(_:RemoteException) { /* A dead binder has no live native operation. */ }
            }
        }
        withContext(Dispatchers.Main) {if(bound) {runCatching {context.unbindService(connection)};bound=false}}
    }
}

fun PdfUtility.analyze(id:String,cpu:Boolean=false,mode:OcrMode?=null,page:Int?=null,rerun:Boolean=false,replaceOcr:Boolean=false):UUID=synchronized(this) {
    requireActiveSession(id)
    val stored=session(id)
    val request=if(mode!=null) org.json.JSONObject().put("mode",mode.name).put("page",page ?: -1)
        .put("request",if(rerun) UUID.randomUUID().toString() else "").put("replaceOcr",replaceOcr)
        .also {stored.put("analysisRequest",it);if(replaceOcr) stored.put("ocrMode",mode.name)}
        else stored.optJSONObject("analysisRequest") ?: org.json.JSONObject().put("mode",OcrMode.LAYOUT_AWARE.name).put("page",-1)
    val job=OneTimeWorkRequestBuilder<AnalysisWorker>().setInputData(workDataOf("session" to id,"cpu" to cpu,
        "mode" to request.getString("mode"),"page" to request.optInt("page",-1),"request" to request.optString("request"),"replaceOcr" to request.optBoolean("replaceOcr")))
        .addTag("analysis-$id").addTag("pdf-analysis").build()
    save(id,stored.put("analysisWorkId",job.id.toString()))
    work.enqueueUniqueWork("folio-heavy-pdf-analysis",ExistingWorkPolicy.APPEND_OR_REPLACE,job)
    job.id
}

/** Page checkpoints live beside the existing copied source; retries skip only validated pages. */
class AnalysisWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pdf-processing","PDF processing",NotificationManager.IMPORTANCE_LOW))
        val notice=NotificationCompat.Builder(applicationContext,"pdf-processing").setSmallIcon(dev.folio.scanner.R.drawable.ic_folio).setContentTitle("Analyzing PDF offline").setOngoing(true).addAction(0,"Cancel",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode(),notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(),notice)
    }
    override suspend fun doWork():Result=withContext(Dispatchers.IO) {
        val session=inputData.getString("session") ?: return@withContext Result.failure()
        val utility=dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext,dev.folio.scanner.pdf.PdfWorkerDependencies::class.java).utility()
        utility.withSessionResources(session) {runSession(session,utility)}
    }
    private suspend fun runSession(session:String,utility:PdfUtility):Result=withContext(Dispatchers.IO) {
        try {
            utility.requireActiveSession(session)
            require(UUID.fromString(session).toString()==session)
            val folder=File(applicationContext.filesDir,"pdf-utility/$session");require(folder.isDirectory)
            setForeground(getForegroundInfo())
            val count=PdfiumDocument(File(folder,"input-0")).use {it.count}
            val mode=OcrMode.valueOf(inputData.getString("mode") ?: OcrMode.LAYOUT_AWARE.name)
            val request=inputData.getString("request").orEmpty()
            val replaceOcr=inputData.getBoolean("replaceOcr",false)
            val indices=ocrTargets(inputData.getInt("page",-1).takeIf {it>=0},count)
            var cpu=inputData.getBoolean("cpu",false) || File(folder,"analysis-cpu").exists()
            var connection:AnalysisConnection?=null
            val scope=this
            fun client()=AnalysisConnection(applicationContext) { text ->scope.launch {setProgress(workDataOf("stage" to text))} }
            try {
                for((done,index) in indices.withIndex()) {
                    ensureActive()
                    setProgress(workDataOf("done" to done,"total" to indices.size,"stage" to "Page ${index+1} of $count"))
                    val checkpoint=File(folder,if(mode==OcrMode.FULL_PAGE) "full-ocr-$index.json" else "analysis-$index.json")
                    if(checkpoint.isFile && File(folder,"analysis-$index.jpg").isFile && runCatching {val stored=org.json.JSONObject(checkpoint.readText());stored.optInt("version")==2 && AnalysisPage.parse(stored).let {it.index==index && it.mode==mode && (request.isEmpty() || it.request==request)}}.getOrDefault(false)) continue
                    try {if(connection==null) {connection=client();connection.start()};connection.page(folder,index,cpu,mode,request,replaceOcr)}
                    catch(error:IOException) {
                        connection?.closeSafely();connection=null
                        if(cpu) throw error
                        cpu=true;File(folder,"analysis-cpu").writeText("Native hardware process failed; retrying with CPU")
                        connection=client();connection.start();connection.page(folder,index,true,mode,request,replaceOcr)
                    } catch(error:RemoteException) {
                        connection?.closeSafely();connection=null
                        if(cpu) throw IOException("CPU analysis process stopped",error)
                        cpu=true;File(folder,"analysis-cpu").writeText("Native hardware process failed; retrying with CPU")
                        connection=client();connection.start();connection.page(folder,index,true,mode,request,replaceOcr)
                    }
                }
                setProgress(workDataOf("done" to indices.size,"total" to indices.size,"stage" to "Analysis complete"))
                Result.success()
            } finally {connection?.closeSafely()}
        } catch(cancel:CancellationException) {throw cancel}
        catch(_:OutOfMemoryError) {Result.failure(workDataOf("error" to "Insufficient memory. Close other apps and retry using CPU."))}
        catch(error:Exception) {Result.failure(workDataOf("error" to (error.message?.take(240) ?: "PDF analysis failed. Retry using CPU.")))}
    }
}

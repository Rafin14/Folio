package dev.folio.scanner.ocr

import android.content.Context
import android.graphics.Matrix
import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.debounce
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

object OcrWork {
    fun enabled(context:Context)=context.getSharedPreferences("ocr-settings",0).getBoolean("automatic",true)
    fun enqueue(context:Context,page:Page,replace:Boolean=false) {
        val request=OneTimeWorkRequestBuilder<OcrWorker>().setInputData(workDataOf("page" to page.id))
            .addTag("ocr").addTag("ocr-${page.documentId}").addTag("ocr-page-${page.id}")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("ocr-page-${page.id}",if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
    }
}
@Singleton
class OcrRepository @Inject constructor(@ApplicationContext private val context:Context,private val db:FolioDatabase,private val documents:DocumentRepository,private val pipeline:ImagePipeline,private val engine:OcrEngine) {
    private val processing=Mutex()
    val automatic=kotlinx.coroutines.flow.MutableStateFlow(OcrWork.enabled(context))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,_ -> })
    @OptIn(FlowPreview::class)
    fun start() { scope.launch {
        db.invalidationTracker.createFlow("ocr","pages","documents",emitInitialState=true).debounce(500).collect {
            val dao=db.documents()
            if(automatic.value) dao.outdatedOcr(OCR_MODEL,OCR_PREPROCESS).forEach { p ->
                db.withTransaction { if(dao.page(p.id)==p) dao.save(OcrResult(p.id,"",System.currentTimeMillis(),status="queued")) }
            }
            dao.pendingOcr().forEach { OcrWork.enqueue(context,it) }
        }
    } }
    fun automatic(enabled:Boolean) { context.getSharedPreferences("ocr-settings",0).edit().putBoolean("automatic",enabled).apply(); automatic.value=enabled }
    suspend fun request(document:String,page:String?=null,force:Boolean=false) = withContext(Dispatchers.IO) {
        val dao=db.documents(); val doc=dao.document(document) ?: return@withContext
        require(doc.trashedAt==null && !doc.deleting)
        dao.pages(document).filter { page==null || it.id==page }.forEach { p ->
            val current=dao.ocr(p.id)
            if(!force && validOcr(current,p)) return@forEach
            if(!force && current?.status in listOf("queued","processing")) { OcrWork.enqueue(context,p); return@forEach }
            db.withTransaction { if(dao.page(p.id)==p) dao.save(OcrResult(p.id,"",System.currentTimeMillis(),status="queued")) }
            OcrWork.enqueue(context,p,force)
        }
    }
    suspend fun clear()=withContext(Dispatchers.IO) {
        automatic(false); WorkManager.getInstance(context).cancelAllWorkByTag("ocr").result.get()
        db.withTransaction { db.documents().allOcr().forEach { db.documents().clearOcr(it.pageId) } }
    }
    suspend fun retryFailed()=withContext(Dispatchers.IO) { db.documents().allOcr().filter { it.status=="failed" }.forEach { r -> db.documents().page(r.pageId)?.let { request(it.documentId,it.id,true) } } }
    suspend fun failedAfterRetries(id:String)=db.withTransaction {
        val dao=db.documents(); dao.ocr(id)?.takeIf { it.status=="queued" || it.status=="processing" }?.let { dao.save(it.copy(status="failed",error="OCR couldn't be completed. Retry when storage is available.")) }
    }
    suspend fun execute(pageId:String):Boolean=processing.withLock { withContext(Dispatchers.IO) {
        val dao=db.documents(); val page=dao.page(pageId) ?: return@withContext true
        val doc=dao.document(page.documentId) ?: return@withContext true
        if(page.trashedAt!=null || doc.deleting || doc.trashedAt!=null || dao.ocr(pageId)==null) return@withContext true
        if(validOcr(dao.ocr(pageId),page)) return@withContext true
        val job=currentCoroutineContext(); val temp=File(context.cacheDir,"ocr-input-$pageId.source")
        try {
            val snapshot=documents.snapshotOcr(pageId,temp)
            if(!sameOcrPage(snapshot,page)) return@withContext true
            dao.save(OcrResult(pageId,"",System.currentTimeMillis(),status="processing"))
            val hash=dev.folio.scanner.backup.hashFile(temp) { job.ensureActive() }
            val corrected=pipeline.correct(temp,decodeCorners(page.crop),2048)
            val image=if(page.rotation==0) corrected else {
                val rotated=Bitmap.createBitmap(corrected,0,0,corrected.width,corrected.height,Matrix().apply { postRotate(page.rotation.toFloat()) },true)
                if(rotated!==corrected) corrected.recycle(); rotated
            }
            val paper = try { pipeline.pageCanvas(image, page.layout()) } catch(failure:Throwable) { image.recycle(); throw failure }
            if(paper !== image) image.recycle()
            val inputSize="${paper.width}x${paper.height}"
            val result=try { engine.recognize(paper) { job.ensureActive() } } finally { paper.recycle() }
            job.ensureActive()
            val confidence=result.regions.map { it.confidence }.average().takeIf { it.isFinite() } ?: 0.0
            val diagnostics="engine=PaddleOCR model=$OCR_MODEL preVersion=$OCR_PREPROCESS input=$inputSize detInput=${result.detectorWidth}x${result.detectorHeight} provider=${result.provider} detProvider=${result.detectionProvider} recProvider=${result.recognitionProvider} available=${result.availableProviders} fallbackReason=${result.fallbackReason} cpuFallback=${result.cpuFallback} threads=2 init=${result.initializationMs} pre=${result.preprocessMs} det=${result.detectionMs} rec=${result.recognitionMs} post=${result.postprocessMs} total=${result.totalMs} regions=${result.regions.size} meanConfidence=$confidence pssKb=${android.os.Debug.getPss()}"
            if(dev.folio.scanner.BuildConfig.DEBUG) android.util.Log.d("FolioOCR",diagnostics)
            db.withTransaction {
                val current=dao.page(pageId); val document=dao.document(page.documentId)
                if(sameOcrPage(current,page) && document?.deleting==false && document.trashedAt==null && dao.ocr(pageId)!=null) {
                    dao.save(OcrResult(pageId,result.regions.joinToString("\n") { it.text },System.currentTimeMillis(),regionsJson(result.regions),"complete",ocrRevision(page,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,page.width,page.height,result.totalMs,diagnostics))
                }
            }; true
        } catch(cancel:CancellationException) { throw cancel }
        catch(e:Exception) {
            val retry=e is java.io.IOException
            db.withTransaction { if(sameOcrPage(dao.page(pageId),page) && dao.ocr(pageId)!=null) dao.save(OcrResult(pageId,"",System.currentTimeMillis(),status=if(retry) "queued" else "failed",error="OCR couldn't be completed. Retry when storage is available.")) }
            if(dev.folio.scanner.BuildConfig.DEBUG) android.util.Log.w("FolioOCR","OCR failure: ${e.javaClass.simpleName}")
            !retry
        } finally { temp.delete() }
    } }
}

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface OcrWorkerDependencies { fun ocr():OcrRepository }
class OcrWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val id=inputData.getString("page") ?: return Result.failure()
        val repo=dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext,OcrWorkerDependencies::class.java).ocr()
        return try { if(repo.execute(id)) Result.success() else if(runAttemptCount<2) Result.retry() else { repo.failedAfterRetries(id); Result.failure() } }
        catch(cancel:CancellationException) { throw cancel }
        catch(_:OutOfMemoryError) { repo.failedAfterRetries(id); Result.failure() }
        catch(_:Exception) { Result.failure() }
    }
}

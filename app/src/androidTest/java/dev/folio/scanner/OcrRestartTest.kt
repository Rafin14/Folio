package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.OcrResult
import dev.folio.scanner.ocr.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import java.io.*
import java.util.concurrent.TimeUnit

/** Also runnable as two separate instrumentation invocations with an OS force-stop between. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class OcrRestartTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val docs get()=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
    private val marker get()=context.getSharedPreferences("ocr-restart-test",0)
    @Test fun aPersistQueuedPageJob()=runBlocking {
        marker.getString("document",null)?.let { old -> if(docs.dao.document(old)!=null) docs.purgeForTest(old) }
        val bitmap=Bitmap.createBitmap(900,1200,Bitmap.Config.ARGB_8888); val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        canvas.drawText("Newton printed document",40f,140f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=42f })
        val bytes=ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }; bitmap.recycle()
        val doc=docs.create("OCR restart acceptance"); val id=docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
        val request=OneTimeWorkRequestBuilder<OcrWorker>().setInitialDelay(30,TimeUnit.SECONDS).setInputData(workDataOf("page" to id))
            .addTag("ocr").addTag("ocr-$doc").build()
        WorkManager.getInstance(context).enqueueUniqueWork("ocr-page-$id",ExistingWorkPolicy.REPLACE,request).result.get()
        docs.dao.save(OcrResult(id,"",System.currentTimeMillis(),status="queued"))
        marker.edit().putString("document",doc).putString("page",id).putString("work",request.id.toString()).commit()
        assertEquals("queued",docs.dao.ocr(id)!!.status)
        assertEquals(WorkInfo.State.ENQUEUED,WorkManager.getInstance(context).getWorkInfoById(request.id).get()!!.state)
    }
    @Test fun bRecoverQueuedPageAndReuseDurableText()=runBlocking {
        val doc=requireNotNull(marker.getString("document",null)); val id=requireNotNull(marker.getString("page",null))
        try {
            withTimeout(90000) { docs.dao.observeOcr(doc).first { it.any { r -> r.pageId==id && r.status=="complete" } } }
            val r=docs.dao.ocr(id)!!; assertTrue(validOcr(r,docs.dao.page(id)!!))
            assertEquals(id,docs.dao.searchOcr(searchExpression("newt")).first().single { it.documentId==doc }.pageId)
            val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork("ocr-page-$id").get()
            assertEquals(1,work.size)
            val repo=EntryPointAccessors.fromApplication(context,OcrWorkerDependencies::class.java).ocr()
            repo.request(doc,id); assertEquals(r.modifiedAt,docs.dao.ocr(id)!!.modifiedAt)
        } finally { docs.purgeForTest(doc); marker.edit().clear().commit() }
    }
}

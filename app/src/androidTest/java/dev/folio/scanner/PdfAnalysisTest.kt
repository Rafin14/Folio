package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class PdfAnalysisTest {
    @Test fun automaticProviderReportsActualExecutionThroughResumableWorker()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=dagger.hilt.android.EntryPointAccessors.fromApplication(context,dev.folio.scanner.pdf.PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/auto-analysis-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {pdf ->PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),18f).moveText(40.0,740.0).showText("Automatic offline Folio analysis").endText()}
        val id=utility.open("analysis",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source)))
        try {
            val job=utility.analyze(id)
            val result=kotlinx.coroutines.withTimeout(300000) {utility.work.getWorkInfoByIdFlow(job).first {it?.state?.isFinished==true}}!!
            assertEquals(result.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,result.state)
            val page=AnalysisPage.parse(JSONObject(File(utility.folder(id),"analysis-0.json").readText()))
            assertTrue(page.text.contains("Automatic offline Folio analysis"))
            assertTrue(page.diagnostics.contains("Actual:"));assertTrue(page.diagnostics.contains("ExecutionProvider"))
            inst.sendStatus(0,android.os.Bundle().apply {putString("pdfAnalysisAuto",page.diagnostics)})
        } finally {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.discard(id);source.delete()}
    }
    @Test fun realLayoutAndExistingOcrHandleNativeAndScannedPagesIncrementally()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.filesDir,"pdf-utility/${UUID.randomUUID()}").apply {mkdirs()}
        val bitmap=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=44f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
            canvas.drawText("FOLIO OFFLINE DOCUMENT",80f,130f,paint)
            repeat(8) {canvas.drawText("Readable scanner text line ${it+1}",80f,250f+it*100,paint)}
            val bytes=ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.JPEG,98,this)}.toByteArray()
            PdfDocument(PdfWriter(File(folder,"input-0"))).use { pdf ->
                val page=pdf.addNewPage();PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(),18f).moveText(50.0,740.0).showText("Native editable Folio text").endText()
                PdfCanvas(pdf.addNewPage()).addImageFittedIntoRectangle(ImageDataFactory.create(bytes),Rectangle(0f,0f,595f,842f),false)
            }
            val connection=AnalysisConnection(context) {}
            try {connection.start();connection.page(folder,0,true);connection.page(folder,1,true)} finally {connection.closeSafely()}
            val native=AnalysisPage.parse(JSONObject(File(folder,"analysis-0.json").readText()))
            val scanned=AnalysisPage.parse(JSONObject(File(folder,"analysis-1.json").readText()))
            assertTrue(native.text.contains("Native editable Folio text"));assertTrue(native.regions.any {it.native})
            assertTrue(scanned.regions.isNotEmpty());assertTrue(scanned.text.contains("OFFLINE",true))
            assertTrue(scanned.diagnostics.contains("CPUExecutionProvider"))
            assertTrue(scanned.diagnostics.contains("PSS:"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply {
                putString("pdfAnalysisProviders",native.diagnostics+"\nScanned page:\n"+scanned.diagnostics)
            })
            assertTrue(File(folder,"analysis-0.jpg").length()>0);assertTrue(File(folder,"analysis-1.jpg").length()>0)
        } finally {bitmap.recycle();folder.deleteRecursively()}
    }
}

package dev.folio.scanner

import android.app.ActivityManager
import android.graphics.*
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class AnalysisRecoveryTest {
    @Test fun interruptedFigureBatchRetriesOnlyRemainingFiles()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        inst.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        val bitmap=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
        val bytes=try {ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.JPEG,95,this)}.toByteArray()} finally {bitmap.recycle()}
        PdfDocument(PdfWriter(File(folder,"input-0"))).use {pdf ->val canvas=PdfCanvas(pdf.addNewPage());canvas.addImageFittedIntoRectangle(ImageDataFactory.create(bytes),Rectangle(50f,600f,200f,100f),false);canvas.addImageFittedIntoRectangle(ImageDataFactory.create(bytes),Rectangle(50f,300f,200f,100f),false)}
        utility.save(id,JSONObject().put("kind","analysis").put("counts",JSONArray(listOf(1))).put("names",JSONArray(listOf("Recovery $id.pdf"))).put("state","editing"))
        val page=PdfiumDocument(File(folder,"input-0")).use {pdf ->val image=pdf.render(0,842);try {val j=pdf.inspect(0,image.width,image.height);AnalysisPage(0,image.width,image.height,595.0,842.0,emptyList(),j.getJSONArray("objects"),"CPU")} finally {image.recycle()}}
        File(folder,"analysis-0.json").writeText(page.json().toString())
        val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")
        var output:Uri?=null
        try {
            context.contentResolver.call(Uri.parse("content://dev.folio.scanner.test.storage"),"fault",null,android.os.Bundle().apply {putInt("after",1)})
            val first=utility.exportAnalysis(id,tree,"figures",JSONObject().put("figures",JSONArray(reconcileFigures(page).map {it.json()})))
            val failed=withTimeout(30000) {utility.work.getWorkInfoByIdFlow(first).first {it?.state?.isFinished==true}!!}
            assertEquals(WorkInfo.State.FAILED,failed.state)
            val receipt=JSONObject(File(folder,"analysis-export.json").readText());output=Uri.parse(receipt.getString("outputFolder"))
            assertEquals(1,receipt.getJSONArray("completed").length());assertEquals("failed",receipt.getString("state"))
            val originalUri=receipt.getJSONObject("outputs").getString("0")
            val second=utility.retryAnalysisExport(id)
            val success=withTimeout(30000) {utility.work.getWorkInfoByIdFlow(second).first {it?.state?.isFinished==true}!!}
            assertEquals(success.outputData.toString(),WorkInfo.State.SUCCEEDED,success.state)
            val completed=JSONObject(File(folder,"analysis-export.json").readText());assertEquals(2,completed.getJSONArray("completed").length());assertEquals(originalUri,completed.getJSONObject("outputs").getString("0"))
            repeat(2) {assertArrayEquals(bytes,context.contentResolver.openInputStream(Uri.parse(completed.getJSONObject("outputs").getString(it.toString())))!!.use {s ->s.readBytes()})}
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(output!!)),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)!!.use {assertEquals(2,it.count)}
        } finally {context.contentResolver.call(Uri.parse("content://dev.folio.scanner.test.storage"),"fault",null,android.os.Bundle().apply {putInt("after",-1)});utility.discard(id);output?.let {DocumentsContract.deleteDocument(context.contentResolver,it)}}
    }

    @Test fun privateProcessDeathRetriesCpuAndKeepsCompletedPage()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        PdfDocument(PdfWriter(File(folder,"input-0"))).use {pdf ->repeat(2) {PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(com.itextpdf.kernel.font.PdfFontFactory.createFont(),18f).moveText(50.0,740.0).showText("Recovery native page ${it+1}").endText()}}
        utility.save(id,JSONObject().put("kind","analysis").put("counts",JSONArray(listOf(2))).put("names",JSONArray(listOf("Recovery.pdf"))).put("state","editing"))
        val bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE)}
        try {File(folder,"analysis-0.jpg").outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,94,it)}} finally {bitmap.recycle()}
        val checkpoint=AnalysisPage(0,595,842,595.0,842.0,listOf(AnalysisRegion(0,"text",Box(0f,0f,595f,842f),0,"Retained checkpoint",native=true)),JSONArray(),"CPU").json().toString()
        File(folder,"analysis-0.json").writeText(checkpoint)
        try {
            val job=utility.analyze(id)
            val pid=withTimeout(30000) {var found=0;while(found==0) {found=context.getSystemService(ActivityManager::class.java).runningAppProcesses?.firstOrNull {it.processName=="${context.packageName}:pdfanalysis"}?.pid ?: 0;delay(20)};found}
            delay(500);android.os.Process.killProcess(pid)
            val result=withTimeout(120000) {utility.work.getWorkInfoByIdFlow(job).first {it?.state?.isFinished==true}!!}
            assertEquals(result.outputData.toString(),WorkInfo.State.SUCCEEDED,result.state)
            assertEquals(checkpoint,File(folder,"analysis-0.json").readText());assertTrue(File(folder,"analysis-cpu").exists())
            val next=AnalysisPage.parse(JSONObject(File(folder,"analysis-1.json").readText()));assertTrue(next.text.contains("native page 2"));assertTrue(next.diagnostics.contains("CPUExecutionProvider"))
        } finally {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.discard(id)}
    }
}

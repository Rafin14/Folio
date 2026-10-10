package dev.folio.scanner

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.io.image.ImageDataFactory
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AnalysisExportCancellationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    @Test fun textWordAndFigureWorkersFinishCurrentPublicationBeforeDiscard()=runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        val authority=Uri.parse("content://dev.folio.scanner.test.storage")
        val resolver=context.contentResolver
        val tree=DocumentsContract.buildTreeDocumentUri(authority.authority!!,"root")
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
        for(type in listOf("text","word","figures")) {
            val id=UUID.randomUUID().toString();val folder=utility.folder(id)
            val bitmap=Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
            val jpg=java.io.ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.JPEG,95,this)}.toByteArray();bitmap.recycle()
            PdfDocument(PdfWriter(File(folder,"input-0"))).use {pdf ->val page=pdf.addNewPage();PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(),18f).moveText(50.0,740.0).showText("Cancellation Word fixture").endText();PdfCanvas(page).addImageFittedIntoRectangle(ImageDataFactory.create(jpg),Rectangle(50f,500f,200f,100f),false)}
            val original=File(folder,"input-0").readBytes()
            utility.save(id,JSONObject().put("kind","analysis").put("counts",JSONArray(listOf(1))).put("names",JSONArray(listOf("Cancellation publication.pdf"))).put("state","editing"))
            val page=PdfiumDocument(File(folder,"input-0")).use {pdf ->val image=pdf.render(0,842);try {File(folder,"analysis-0.jpg").outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,95,it)};val inspected=pdf.inspect(0,image.width,image.height);AnalysisPage(0,image.width,image.height,595.0,842.0,listOf(AnalysisRegion(0,"paragraph_title",Box(40f,60f,500f,120f),0,"Cancellation Word fixture",18.0,true,true)),inspected.getJSONArray("objects"),"Synthetic layout fixture / actual native export",inspected.getJSONArray("characters"))} finally {image.recycle()}}
            File(folder,"analysis-0.json").writeText(page.json().toString())
            val target=if(type=="figures") tree else DocumentsContract.createDocument(resolver,parent,if(type=="word") "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "text/plain","cancel.$type")!!
            val extra=JSONObject().put("mode",WordMode.EDITABLE.name).put("figures",JSONArray(reconcileFigures(page).map {it.json()}))
            var outputFolder:Uri?=null
            try {
                resolver.call(authority,"writeDelay",null,Bundle().apply {putInt("ms",1200)})
                val work=utility.exportAnalysis(id,target,type,extra)
                withTimeout(30000) {while(resolver.call(authority,"writeStatus",null,null)?.getBoolean("writing")!=true) delay(10)}
                assertFalse(utility.work.getWorkInfoById(work).get()!!.state.isFinished)
                // SAF has entered publication. Cancellation must wait for the complete verified file.
                val request=JSONObject(File(folder,"analysis-export.json").readText())
                val published=if(type=="figures") {
                    outputFolder=Uri.parse(request.getString("outputFolder"));Uri.parse(request.getJSONObject("outputs").getString("0"))
                } else target
                utility.cancelAndDiscard(id)
                assertFalse(folder.exists());assertEquals(androidx.work.WorkInfo.State.CANCELLED,utility.work.getWorkInfoById(work).get()!!.state)
                val bytes=resolver.openInputStream(published)!!.use {it.readBytes()}
                when(type) {
                    "text" -> assertEquals("Cancellation Word fixture",String(bytes).trim())
                    "figures" -> assertArrayEquals(jpg,bytes)
                    "word" -> {val file=File(context.cacheDir,"cancel-completed.docx");try {file.writeBytes(bytes);java.util.zip.ZipFile(file).use {assertNotNull(it.getEntry("word/document.xml"))}} finally {file.delete()}}
                }
                // Source was a private copied PDF; no Folio documents were imported by these exports.
                assertTrue(original.isNotEmpty())
            } finally {
                resolver.call(authority,"writeDelay",null,Bundle().apply {putInt("ms",0)})
                utility.cancelAndDiscard(id);if(type!="figures") DocumentsContract.deleteDocument(resolver,target);outputFolder?.let {DocumentsContract.deleteDocument(resolver,it)}
            }
        }
    }
    @Test fun docxConversionCancellationRemovesIncompleteZipInBothModes() {
        val folder=File(context.cacheDir,"word-cancel-${UUID.randomUUID()}").apply {mkdirs()}
        try {for(mode in WordMode.entries) for(late in listOf(false,true)) {
            val output=File(folder,"cancel.docx");var stopped=false
            val paragraph=WordParagraph(Box(20f,20f,300f,100f),0,listOf(WordRun("Editable cancellation text")))
            val failure=runCatching {writeDocx(output,folder,4,mode,{WordPage(595.0,842.0,595,842,listOf(paragraph))},{if(stopped) throw CancellationException("Test cancellation")},{done,_->if(done==(if(late) 3 else 0)) stopped=true})}.exceptionOrNull()
            assertTrue(failure is CancellationException);assertFalse(output.exists());assertFalse(File(folder,"word-document.xml").exists())
        }} finally {folder.deleteRecursively()}
    }
}

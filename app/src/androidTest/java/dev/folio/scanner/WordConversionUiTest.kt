package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.pdfanalysis.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class WordConversionUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun modesThemesSharedAnalysisAndVerifiedBackgroundExport() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        PdfDocument(PdfWriter(File(folder,"input-0"))).use {pdf ->PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),18f).moveText(50.0,740.0).showText("Editable acceptance").endText()}
        utility.save(id,JSONObject().put("kind","analysis").put("analysisTool","word").put("counts",JSONArray(listOf(1))).put("names",JSONArray(listOf("Word acceptance.pdf"))).put("state","editing"))
        PdfiumDocument(File(folder,"input-0")).use {pdf ->val image=pdf.render(0,842);try {
            File(folder,"analysis-0.jpg").outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,94,it)}
            File(context.getExternalFilesDir(null),"major-update-word-source.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}
            val inspected=pdf.inspect(0,image.width,image.height)
            File(folder,"analysis-0.json").writeText(AnalysisPage(0,image.width,image.height,595.0,842.0,listOf(AnalysisRegion(0,"doc_title",Box(40f,65f,400f,120f),0,"Editable acceptance",18.0,true,true)),inspected.getJSONArray("objects"),"CPUExecutionProvider",inspected.getJSONArray("characters")).json().toString())
        } finally {image.recycle()}}
        val before=runBlocking {utility.documents.dao.allDocuments().size}
        try {
            val job=utility.analyze(id,true)
            runBlocking {withTimeout(30000) {utility.work.getWorkInfoByIdFlow(job).first {it?.state?.isFinished==true}}}
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {key(theme) {FolioTheme(theme) {PdfAnalysisScreen(model,"word",id) {}}}}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("Editable",substring=false).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Layout-preserving",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Convert and export DOCX").performScrollTo().assertIsEnabled()
                compose.onNodeWithText("Editable",substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Output filename").performScrollTo().performTextReplacement("Acceptance export")
                compose.onNodeWithText("Convert and export DOCX").performScrollTo()
                compose.waitForIdle()
                inst.uiAutomation.takeScreenshot().let {b ->File(context.getExternalFilesDir(null),"major-update-word-$theme.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            }
            compose.onNodeWithContentDescription("Analysis tools").performClick();compose.onNodeWithText("OCR PDF",substring=false).performClick()
            compose.onNodeWithText("Text",substring=false).performClick();compose.onNodeWithText("Editable acceptance").assertIsDisplayed()
            compose.onNodeWithContentDescription("Analysis tools").performClick();compose.onNodeWithText("PDF to Word",substring=false).performClick()
            assertEquals(1,utility.work.getWorkInfosByTag("analysis-$id").get().size)
            WordMode.entries.forEach {mode ->
                val target=File(context.cacheDir,"shared-pdfs/$id-${mode.name}.docx").apply {parentFile!!.mkdirs()}
                try {
                    val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",target)
                    val exported=utility.exportAnalysis(id,uri,"word",JSONObject().put("mode",mode.name))
                    val result=runBlocking {withTimeout(30000) {utility.work.getWorkInfoByIdFlow(exported).first {it?.state?.isFinished==true}!!}}
                    assertEquals(result.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,result.state)
                    assertEquals("complete",JSONObject(File(folder,"analysis-export.json").readText()).getString("state"))
                    ZipFile(target).use {zip ->val xml=zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use {it.readText()};assertTrue(xml.contains("Editable acceptance"));assertEquals(mode==WordMode.LAYOUT,xml.contains("framePr"))}
                } finally {target.delete()}
            }
            assertEquals(before,runBlocking {utility.documents.dao.allDocuments().size})
        } finally {runBlocking {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.work.cancelAllWorkByTag("analysis-export-$id").result.get();utility.discard(id)}}
    }
}

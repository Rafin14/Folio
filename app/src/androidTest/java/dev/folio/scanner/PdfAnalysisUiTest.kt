package dev.folio.scanner

import android.graphics.*
import android.content.ClipboardManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.pdfanalysis.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfAnalysisUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun textNavigationToAnUnpreparedPageKeepsAnimatedContentSafe() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        utility.save(id,JSONObject().put("kind","analysis").put("analysisTool","ocr").put("counts",JSONArray(listOf(2))).put("names",JSONArray(listOf("Pending page.pdf"))).put("state","editing"))
        File(folder,"analysis-0.json").writeText(AnalysisPage(0,595,842,595.0,842.0,listOf(AnalysisRegion(0,"text",Box(45f,85f,450f,135f),0,"Completed page text",native=true)),JSONArray(),"CPUExecutionProvider").json().toString())
        try {
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->
                    val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(theme) {FolioTheme(theme) {PdfAnalysisScreen(model,"ocr",id) {}}}}
                }
                compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Copy page text").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Text",substring=false).performClick()
                compose.onNodeWithText("Completed page text").assertIsDisplayed()
                repeat(3) {
                    compose.onNodeWithContentDescription("Next page").performClick()
                    compose.waitUntil(10000) {compose.onAllNodesWithText("Start or retry analysis to read this page.").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithText("2 of 2").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Copy page text").assertDoesNotExist()
                    compose.onNodeWithContentDescription("Previous page").performClick()
                    compose.waitUntil(10000) {compose.onAllNodesWithText("Completed page text").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithText("1 of 2").assertIsDisplayed()
                    compose.onNodeWithContentDescription("Copy page text").assertIsDisplayed()
                }
            }
        } finally {runBlocking {utility.discard(id)}}
    }
    @Test fun pageTextFullscreenCopyAndThemesUsePersistentAnalysis() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val id=UUID.randomUUID().toString();val folder=utility.folder(id)
        PdfDocument(PdfWriter(File(folder,"input-0"))).use {it.addNewPage();it.addNewPage()}
        utility.save(id,JSONObject().put("kind","analysis").put("analysisTool","ocr").put("counts",JSONArray(listOf(2))).put("names",JSONArray(listOf("OCR acceptance.pdf"))).put("state","editing"))
        repeat(2) {i ->
            val bitmap=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888);Canvas(bitmap).apply {drawColor(Color.WHITE);drawText("Readable page ${i+1}",50f,120f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=24f})}
            File(folder,"analysis-$i.jpg").outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
            File(folder,"analysis-$i.json").writeText(AnalysisPage(i,595,842,595.0,842.0,listOf(AnalysisRegion(0,"text",Box(45f,85f,450f,135f),0,"Readable page ${i+1}",native=true)),JSONArray(),"CPUExecutionProvider").json().toString())
        }
        try {
            val before=runBlocking {utility.documents.dao.allDocuments().size}
            val analysis=utility.analyze(id,true)
            val completed=runBlocking {withTimeout(30000) {utility.work.getWorkInfoByIdFlow(analysis).first {it?.state?.isFinished==true}!!}}
            assertEquals(completed.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,completed.state)
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {key(theme) {FolioTheme(theme) {PdfAnalysisScreen(model,"ocr",id) {}}}}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("1 of 2").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("Analysis tools").performClick()
                compose.onAllNodesWithText("OCR PDF").assertCountEquals(3)
                val header=compose.onAllNodesWithText("OCR PDF").filter(!hasClickAction())
                assertTrue("Context header is not an action",header.fetchSemanticsNodes().isNotEmpty())
                androidx.test.uiautomator.UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"analysis-menu-$theme.png"))
                androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack()
                compose.onNodeWithText("Text",substring=false).performClick();compose.onNodeWithText("Readable page 1").assertIsDisplayed()
                compose.onNodeWithContentDescription("Copy page text").performClick()
                compose.runOnUiThread {assertEquals("Readable page 1",context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())}
                compose.onNodeWithContentDescription("Next page").performClick();compose.onNodeWithText("Readable page 2").assertIsDisplayed()
                compose.onNodeWithContentDescription("Fullscreen text").performClick();compose.onNodeWithText("Extracted text").assertIsDisplayed()
                compose.onNodeWithContentDescription("Fullscreen text").performClick()
                compose.onNodeWithText("Page",substring=false).performClick();compose.onNodeWithContentDescription("Previous page").performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("PDF page with layout regions").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("PDF page with layout regions").performTouchInput {val fit=FitTransform.create(595,842,width.toFloat(),height.toFloat());click(androidx.compose.ui.geometry.Offset(fit.x+150*fit.scale,fit.y+110*fit.scale))}
                compose.onNodeWithText("Copy region text").assertIsDisplayed().performClick()
                androidx.test.uiautomator.UiDevice.getInstance(inst).pressBack()
                compose.waitForIdle();Thread.sleep(7000)
                inst.uiAutomation.takeScreenshot().let {b ->File(context.getExternalFilesDir(null),"major-update-ocr-$theme.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            }
            val target=File(context.cacheDir,"shared-pdfs/analysis-$id.txt").apply {parentFile!!.mkdirs()}
            try {
                val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",target)
                val exported=utility.exportAnalysis(id,uri,"text")
                val result=runBlocking {withTimeout(30000) {utility.work.getWorkInfoByIdFlow(exported).first {it?.state?.isFinished==true}!!}}
                assertEquals(result.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,result.state)
                assertTrue(target.readText().contains("Readable page 1"));assertTrue(target.readText().contains("Readable page 2"))
                assertEquals(before,runBlocking {utility.documents.dao.allDocuments().size})
            } finally {target.delete()}
        } finally {runBlocking {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.work.cancelAllWorkByTag("analysis-export-$id").result.get();utility.discard(id)}}
    }
}

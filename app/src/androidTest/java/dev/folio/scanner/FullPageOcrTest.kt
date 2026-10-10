package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
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
import java.io.*
import java.util.UUID

class FullPageOcrTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun fullPageScopeRealDetectionRecognitionCheckpointRetryAndModeUi()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/full-ocr-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {pdf ->repeat(2) {index ->
            val bitmap=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
            val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=52f;typeface=Typeface.DEFAULT}
            canvas.drawText("FOLIO OFFLINE PAGE ${index+1}",70f,150f,ink)
            canvas.drawText("Full page detection and recognition",70f,350f,ink)
            canvas.drawText("Readable text beyond layout regions",70f,750f,ink)
            val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,98,it);it.toByteArray()};bitmap.recycle()
            PdfCanvas(pdf.addNewPage()).addImageFittedIntoRectangle(ImageDataFactory.create(bytes),Rectangle(0f,0f,595f,842f),false)
        }}
        val original=source.readBytes()
        val id=utility.open("analysis",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)))
        val folder=utility.folder(id)
        suspend fun await(job:UUID) {
            val result=withTimeout(300000) {utility.work.getWorkInfoByIdFlow(job).first {it?.state?.isFinished==true}}!!
            assertEquals(result.outputData.toString(),androidx.work.WorkInfo.State.SUCCEEDED,result.state)
        }
        try {
            repeat(2) {index ->File(folder,"analysis-$index.json").writeText(AnalysisPage(index,1200,1600,595.0,842.0,
                listOf(AnalysisRegion(0,"text",Box(0f,0f,100f,100f),0,"Layout checkpoint $index")),JSONArray(),"layout cache").json().toString())}
            val layout=List(2) {File(folder,"analysis-$it.json").readBytes()}
            await(utility.analyze(id,true,OcrMode.FULL_PAGE,page=1,rerun=true,replaceOcr=true))
            assertFalse(File(folder,"full-ocr-0.json").exists())
            val full=AnalysisPage.parse(JSONObject(ocrResultFile(folder,1).readText()))
            assertEquals(OcrMode.FULL_PAGE,full.mode);assertEquals(1,full.index)
            assertTrue(full.text.contains("FOLIO",true));assertTrue(full.text.contains("beyond",true))
            assertTrue(full.regions.size>=3);assertTrue(full.regions.all {it.label=="text" && !it.native})
            assertTrue(full.diagnostics.contains("Layout detection bypassed"));assertFalse(full.diagnostics.contains("PP-DocLayoutV3"))
            assertTrue(full.diagnostics.contains("CPUExecutionProvider"))
            repeat(2) {assertArrayEquals(layout[it],File(folder,"analysis-$it.json").readBytes())}
            await(utility.analyze(id,true,OcrMode.FULL_PAGE,rerun=true,replaceOcr=true))
            val checkpoints=List(2) {File(folder,"full-ocr-$it.json").readBytes()}
            val exportedText=File(context.cacheDir,"shared-pdfs/full-page-$id.txt").apply {parentFile!!.mkdirs()}
            try {
                await(utility.exportAnalysis(id,androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",exportedText),"text"))
                val text=exportedText.readText()
                assertTrue(text.contains("FOLIO",true));assertTrue(text.contains("beyond",true))
                assertFalse(text.contains("Layout checkpoint"))
                assertEquals(2,Regex("FOLIO",RegexOption.IGNORE_CASE).findAll(text).count())
            } finally {exportedText.delete()}

            await(utility.analyze(id,true))
            repeat(2) {assertArrayEquals(checkpoints[it],File(folder,"full-ocr-$it.json").readBytes())}
            compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {FolioTheme("Light") {PdfAnalysisScreen(model,"ocr",id) {}}}}
            compose.waitUntil(15000) {compose.onAllNodesWithText("Page result: Full Page OCR").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Next page").performClick()
            compose.onNodeWithText("OCR Current Page").performClick()
            assertEquals(1,utility.session(id).getJSONObject("analysisRequest").getInt("page"))
            await(UUID.fromString(utility.session(id).getString("analysisWorkId")))
            assertArrayEquals(checkpoints[0],File(folder,"full-ocr-0.json").readBytes())
            compose.waitUntil(15000) {compose.onAllNodes(hasText("Layout-Aware OCR",substring=false) and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Layout-Aware OCR",substring=false).performClick()
            compose.onNodeWithText("OCR Current Page").performClick()
            await(UUID.fromString(utility.session(id).getString("analysisWorkId")))
            assertFalse(File(folder,"full-ocr-1.json").exists())
            assertArrayEquals(checkpoints[0],File(folder,"full-ocr-0.json").readBytes())
            assertEquals(OcrMode.LAYOUT_AWARE,AnalysisPage.parse(JSONObject(ocrResultFile(folder,1).readText())).mode)
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(theme) {FolioTheme(theme) {PdfAnalysisScreen(model,"ocr",id) {}}}}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("Page result: Full Page OCR").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Full Page OCR",substring=false).performClick()
                compose.onNode(hasText("Full Page OCR",substring=false) and isSelected()).assertIsDisplayed()
                compose.onNodeWithText("OCR Current Page").assertIsEnabled()
                compose.onNodeWithText("OCR Entire Document").assertIsEnabled()
                compose.onNodeWithText("Show text boundaries").assertIsDisplayed()
                compose.waitForIdle();Thread.sleep(350)
                assertTrue(UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"additional-full-ocr-$theme.png")))
                compose.onNodeWithText("Text",substring=false).performClick()
                compose.onNodeWithContentDescription("Copy page text").assertIsEnabled()
                compose.onNodeWithContentDescription("Next page").performClick()
                compose.onNodeWithText("2 of 2").assertIsDisplayed()
            }
            assertArrayEquals(original,source.readBytes())
            inst.sendStatus(0,android.os.Bundle().apply {putString("fullPageOcrDiagnostics",full.diagnostics)})
        } finally {utility.work.cancelAllWorkByTag("analysis-$id").result.get();utility.discard(id);source.delete()}
    }
}

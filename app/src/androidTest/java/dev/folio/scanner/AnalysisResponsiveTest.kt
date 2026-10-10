package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.pdfanalysis.*
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AnalysisResponsiveTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun landscapeKeepsPageVisibleAndOptionsAccessibleAcrossThemesAndLargeFont() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val device=UiDevice.getInstance(inst)
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/responsive-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {it.addNewPage()}
        val id=runBlocking {utility.open("analysis",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)))}
        val folder=utility.folder(id)
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {drawColor(Color.WHITE);drawText("FOLIO OCR PREVIEW",45f,120f,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=42f})}
        File(folder,"analysis-0.jpg").outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
        File(folder,"full-ocr-0.json").writeText(AnalysisPage(0,800,1000,595.0,842.0,listOf(AnalysisRegion(0,"text",Box(40f,70f,600f,135f),0,"FOLIO OCR PREVIEW")),JSONArray(),"Cached UI fixture",mode=OcrMode.FULL_PAGE).json().toString())
        try {
            device.setOrientationLeft()
            compose.waitUntil(15000) {compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(theme) {val density=LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {FolioTheme(theme) {PdfAnalysisScreen(model,"ocr",id) {}}}}}}
                compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page with text regions").fetchSemanticsNodes().isNotEmpty()}
                val preview=compose.onNodeWithContentDescription("PDF page with text regions").assertIsDisplayed().getUnclippedBoundsInRoot()
                assertTrue("Useful landscape preview height",(preview.bottom-preview.top).value>=80f)
                compose.onNodeWithText("OCR options").performClick()
                compose.onNodeWithText("Full Page OCR",substring=false).assertIsDisplayed().performClick().assertIsSelected()
                compose.onNodeWithText("OCR Current Page").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("OCR Entire Document").assertIsDisplayed().assertIsEnabled()
                compose.onNodeWithText("Show boundaries").assertIsDisplayed()
                compose.onNodeWithText("Done").assertIsDisplayed()
                compose.waitForIdle();Thread.sleep(350)
                assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null),"additional-landscape-$theme-options.png")))
                compose.onNodeWithText("Done").performClick();compose.waitForIdle();Thread.sleep(350)
                assertTrue(device.takeScreenshot(File(context.getExternalFilesDir(null),"additional-landscape-$theme-page.png")))
                compose.onNodeWithText("Export TXT").assertIsDisplayed().assertIsEnabled()
            }
        } finally {device.setOrientationNatural();device.unfreezeRotation();runBlocking {utility.discard(id)};source.delete()}
    }
}

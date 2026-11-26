package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import dev.folio.scanner.ui.*
import dev.folio.scanner.processing.Geometry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ScannerLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun canvasZoomComparisonAndAdjustmentsInBothThemesAndLargeFonts() {
        lateinit var model: LibraryViewModel
        compose.runOnUiThread { model=ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
        val image=Bitmap.createBitmap(1800,2400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.LTGRAY) }
        val bytes=try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { image.recycle() }
        val document=runBlocking { model.repository.create("Canvas acceptance") }
        val draft=runBlocking { model.repository.stageScan(document,ByteArrayInputStream(bytes)).also { model.repository.cropDraft(it,Geometry.full) } }
        var theme by mutableStateOf("Light"); var large by mutableStateOf(false); var cropping by mutableStateOf(false)
        compose.runOnUiThread {
            compose.activity.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,if(large) 1.6f else 1f)) {
                    FolioTheme(theme) {
                        if(cropping) CropScreen(draft,model,{}, { cropping=false },draft=true)
                        else PageEditor(draft,model,{}, { cropping=true },draft=true, retake={})
                    }
                }
            }
        }
        fun custom(node: SemanticsNodeInteraction,label: String) {
            val action=node.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label==label }
            compose.runOnUiThread { assertTrue(action.action()) }
        }
        fun screenshot(name:String) {
            val inst=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val device=androidx.test.uiautomator.UiDevice.getInstance(inst)
            val file=java.io.File(inst.targetContext.getExternalFilesDir(null),"folio-layout-$name.png")
            assertTrue(device.takeScreenshot(file))
            device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/${file.name}")
        }
        try {
            for(mode in listOf("Light","Dark","AMOLED")) for(font in listOf(false,true)) {
                compose.runOnUiThread { theme=mode; large=font }
                compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
                val page=compose.onNodeWithContentDescription("Enhanced page preview")
                page.assertIsDisplayed()
                val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue("Preview should use most of the height",page.fetchSemanticsNode().boundsInRoot.height>root.height*.5f)
                assertTrue("Comparison must leave the paper clear",
                    compose.onNodeWithText("After").fetchSemanticsNode().boundsInRoot.top >= page.fetchSemanticsNode().boundsInRoot.bottom)
                val compareBounds = compose.onNodeWithText("After").fetchSemanticsNode().boundsInRoot
                val undoBounds = compose.onNodeWithContentDescription("Undo filter edit").fetchSemanticsNode().boundsInRoot
                assertTrue("Comparison and history share the bottom tray",
                    compareBounds.right <= undoBounds.left && kotlin.math.abs(compareBounds.center.y - undoBounds.center.y) < 8f)
                val acceptBounds = compose.onNodeWithText("Accept page").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                if (!font) {
                    assertTrue("Accept page stays on the same row at standard text size",
                        kotlin.math.abs(acceptBounds.center.y - undoBounds.center.y) < 8f)
                    compose.onNodeWithText("Retake").assertIsDisplayed()
                    compose.onNodeWithText("Page size").assertIsDisplayed()
                }
                if (mode == "AMOLED") screenshot("comparison-${if(font) "large" else "normal"}")
                custom(page,"Zoom in")
                page.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Zoom 1.5 times"))
                custom(page,"Reset zoom")
                compose.onNodeWithText("After").assertIsSelected()
                compose.onNodeWithText("Before").assertIsNotSelected().performClick().assertIsSelected()
                compose.onNodeWithContentDescription("Original page preview").assertIsDisplayed()
                compose.onNodeWithText("After").assertIsNotSelected().performClick().assertIsSelected()
                compose.onNodeWithText("Adjustments").performScrollTo().performClick()
                compose.waitForIdle()
                compose.onNodeWithText("After").assertIsDisplayed()
                assertEquals(compareBounds, compose.onNodeWithText("After").fetchSemanticsNode().boundsInRoot)
                custom(page,"Zoom in"); custom(page,"Reset zoom")
                val previewBounds=page.fetchSemanticsNode().boundsInRoot
                val controlsBounds=compose.onNodeWithContentDescription("Adjustment controls").fetchSemanticsNode().boundsInRoot
                assertTrue("Controls must not cover preview",previewBounds.right<=controlsBounds.left+1 || previewBounds.bottom<=controlsBounds.top+1)
                screenshot("${mode.lowercase()}-${if(font) "large" else "normal"}")
                compose.onNodeWithContentDescription("Shadow normalization").performScrollTo().assertIsDisplayed()
                assertEquals("Scrolling controls must not move preview",previewBounds,page.fetchSemanticsNode().boundsInRoot)
                compose.onNodeWithContentDescription("Brightness").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Brightness").performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
                compose.waitUntil(30000) { compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Done").assertIsDisplayed().performClick()
                compose.onNodeWithText("Crop",substring=false).performScrollTo().performClick()
                val crop=compose.onNodeWithContentDescription("Crop corners")
                compose.waitUntil(15000) { crop.isDisplayed() }; crop.assertIsDisplayed()
                custom(crop,"Zoom in")
                custom(crop,"Reset zoom")
                compose.onNodeWithText("Confirm crop").performClick()
                compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
            }
        } finally { runBlocking { model.repository.purgeForTest(document) } }
    }
}

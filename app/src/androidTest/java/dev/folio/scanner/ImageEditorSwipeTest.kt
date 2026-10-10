package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.folio.scanner.ui.*
import dev.folio.scanner.processing.Enhancement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class ImageEditorSwipeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun navigationSavesEditsStopsAtBoundsAndYieldsToZoomAcrossThemes() {
        lateinit var model:LibraryViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[LibraryViewModel::class.java]}
        val bitmap=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.LTGRAY)}
        val bytes=ByteArrayOutputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,95,it);it.toByteArray()};bitmap.recycle()
        val doc=runBlocking {model.repository.create("Image swipe acceptance")}
        val pages=runBlocking {repeat(3) {val draft=model.repository.stageScan(doc,ByteArrayInputStream(bytes));model.repository.acceptDraft(draft,Enhancement(),0)};model.repository.dao.pages(doc)}
        fun ready(index:Int) {
            compose.waitUntil(30000) {compose.onAllNodesWithText("$index of 3").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodes(hasText("Save") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        }
        fun swipe(next:Boolean) {compose.onNodeWithContentDescription("Enhanced page preview").performTouchInput {if(next) swipeLeft() else swipeRight()}}
        try {
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity -> activity.setContent {key(theme) {FolioTheme(theme) {PageEditor(pages[0].id,model,{}, {})}}}}
                ready(1);swipe(false);ready(1)
                compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick()
                ready(1);swipe(true);ready(2)
                assertEquals(90,runBlocking {model.repository.dao.page(pages[0].id)!!.rotation})
                val canvas=compose.onNodeWithContentDescription("Enhanced page preview")
                val zoom=canvas.fetchSemanticsNode().config[SemanticsActions.CustomActions].first {it.label=="Zoom in"}
                compose.runOnUiThread {assertTrue(zoom.action())};swipe(true);ready(2)
                val reset=canvas.fetchSemanticsNode().config[SemanticsActions.CustomActions].first {it.label=="Reset zoom"}
                compose.runOnUiThread {assertTrue(reset.action())};swipe(true);ready(3);swipe(true);ready(3)
                val inst=InstrumentationRegistry.getInstrumentation()
                assertTrue(UiDevice.getInstance(inst).takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"additional-image-swipe-$theme.png")))
                swipe(false);ready(2);swipe(false);ready(1)
                runBlocking {model.repository.edit(pages[0].id,rotation=0)}
            }
        } finally {runBlocking {model.repository.purgeForTest(doc)}}
    }
}

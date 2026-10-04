package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ocr.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class WorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun searchContextThumbnailToggleBackRenameResizeAndEditorShare() {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val device=UiDevice.getInstance(inst)
        val doc=runBlocking { docs.create("Workspace acceptance") }
        fun screenshot(name:String) {
            compose.waitForIdle()
            device.waitForIdle()
            val image=File(context.getExternalFilesDir(null),"folio-workspace-$name.png")
            assertTrue(device.takeScreenshot(image))
            device.executeShellCommand("cp ${image.absolutePath} /sdcard/Download/${image.name}")
        }
        fun assertThumbnailInk() {
            val pixels=compose.onNodeWithContentDescription("Search preview Page 1").captureToImage().toPixelMap()
            assertTrue("The search thumbnail must render the printed fixture, not a placeholder",(0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x,y].let { it.alpha>.9f && it.red<.5f && it.green<.5f && it.blue<.5f } } })
        }
        val bitmap=Bitmap.createBitmap(700,1000,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            val canvas=Canvas(this); val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=38f }
            canvas.drawText("Newton's laws",48f,100f,ink); ink.textSize=24f
            canvas.drawText("Physics notes",48f,145f,ink)
            for(y in 220..880 step 55) { canvas.drawText("Force = mass x acceleration",48f,y.toFloat(),ink) }
        }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { bitmap.recycle() }
        try {
            val page=runBlocking {
                val id=docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false); val p=docs.dao.page(id)!!
                val hash=dev.folio.scanner.backup.hashFile(File(p.originalImageUri))
                docs.dao.save(OcrResult(id,"quasar receipt",1,regionsJson(listOf(TextRegion("quasar receipt",listOf(.1,.1,.9,.1,.9,.2,.1,.2),.9))),"complete",ocrRevision(p,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,p.width,p.height))
                id
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Workspace acceptance").fetchSemanticsNodes().isNotEmpty() }
            if(compose.onAllNodesWithContentDescription("Grid view").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Grid view").performClick()
            compose.onNodeWithContentDescription("List view").assertIsDisplayed()
            compose.onNodeWithText("Search documents").performTextInput("quasar")
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Search preview Page 1").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Search preview Page 1").filter(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Preview loaded")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search preview Page 1").assertIsDisplayed(); assertThumbnailInk(); screenshot("search-grid")
            compose.onNodeWithContentDescription("List view").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Search preview Page 1").filter(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Preview loaded")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Grid view").assertIsDisplayed(); assertThumbnailInk(); screenshot("search-list")
            compose.onNodeWithText("quasar").performImeAction()
            compose.onNodeWithContentDescription("Close search").assertIsDisplayed()
            compose.onNodeWithText("Page 1").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Match").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Preview page 1").assertIsDisplayed(); screenshot("match")
            compose.onAllNodesWithContentDescription("Enhanced page preview").assertCountEquals(0)
            compose.onNodeWithContentDescription("Actions for page 1").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Rename").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rename").performScrollTo().performClick()
            compose.onNodeWithText("Page name").performTextInput("Introduction")
            compose.onNodeWithText("Save").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Introduction").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Introduction",runBlocking { docs.dao.page(page)!!.pageName })
            compose.onNodeWithContentDescription("Preview Introduction").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Page size").performScrollTo().performClick()
            compose.onNodeWithText("Letter").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasText("Letter") and isSelected()).fetchSemanticsNodes().isNotEmpty() }; screenshot("paper-sheet")
            compose.onNodeWithText("Apply page size").performScrollTo().performClick()
            compose.waitForIdle()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Share page").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            screenshot("editor-letter"); compose.onNodeWithContentDescription("Share page").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Share as Images").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Share as Images").performScrollTo().performClick()
            assertTrue(device.wait(Until.hasObject(By.textContains("Sharing")),15000) || device.wait(Until.hasObject(By.text("Quick Share")),5000))
            device.waitForIdle(); screenshot("sharesheet"); device.pressBack()
            compose.waitUntil(15000) { compose.activity.lifecycle.currentState==androidx.lifecycle.Lifecycle.State.RESUMED && !device.hasObject(By.textContains("Sharing")) }
            compose.waitForIdle()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Letter",runBlocking { docs.dao.page(page)!!.pageSize })
            compose.waitUntil(15000) { compose.onAllNodesWithText("Save").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Save").performClick()
            try { compose.waitUntil(15000) { compose.onAllNodesWithText("Introduction").fetchSemanticsNodes().isNotEmpty() } }
            catch(failure:Throwable) { screenshot("save-failure"); compose.onRoot().printToLog("Workspace failure"); throw failure }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Close search").performClick()
            compose.onNodeWithText("Search documents").assertIsDisplayed()
            compose.onNodeWithContentDescription("Grid view").performClick()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("List view").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Search documents").performClick()
            compose.waitUntil(15000) { device.hasObject(By.pkg("com.google.android.inputmethod.latin")) }
            compose.waitForIdle()
            device.pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Close search").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Workspace acceptance").assertExists()
            compose.onNodeWithText("Search documents").performTextInput("Workspace")
            compose.waitUntil(15000) { device.hasObject(By.pkg("com.google.android.inputmethod.latin")) }
            device.pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Close search").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithText("Search documents").assertIsDisplayed(); screenshot("home")
        } finally { device.pressBack(); runBlocking { docs.purgeForTest(doc) } }
    }
    @Test fun searchScrollsToMatchingPageBeyondFirstViewport() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val doc=runBlocking { docs.create("Distant match acceptance") }
        val image=Bitmap.createBitmap(300,450,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes=try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { image.recycle() }
        try {
            runBlocking {
                val first=docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
                var last=first
                repeat(9) { last=docs.duplicatePage(first) }
                val page=docs.dao.page(last)!!; val hash=dev.folio.scanner.backup.hashFile(File(page.originalImageUri))
                docs.dao.save(OcrResult(last,"galaxy distant",1,regionsJson(emptyList()),"complete",ocrRevision(page,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,page.width,page.height))
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Distant match acceptance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Search documents").performTextInput("galaxy")
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Search preview Page 10").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Search preview Page 10").assertIsDisplayed().performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Preview page 10").fetchSemanticsNodes().any { it.boundsInRoot.height>0 } }
            compose.onNodeWithContentDescription("Preview page 10").assertIsDisplayed()
            compose.onNodeWithText("Match").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Enhanced page preview").assertCountEquals(0)
        } finally { runBlocking { docs.purgeForTest(doc) } }
    }
}

package dev.folio.scanner

import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ocr.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OcrUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun manualRecognitionCopyShareSearchAndPageNavigationSurviveRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val ocr=EntryPointAccessors.fromApplication(context,OcrWorkerDependencies::class.java).ocr()
        val image=Bitmap.createBitmap(1000,1400,Bitmap.Config.ARGB_8888); val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
        canvas.drawText("Newton invoice 12345",60f,180f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=48f })
        val bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }; image.recycle()
        val doc=runBlocking { docs.create("Text flow acceptance") }; val device=UiDevice.getInstance(instrumentation)
        try {
            val page=runBlocking { docs.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false) }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Text flow acceptance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Text flow acceptance").performClick(); compose.onNodeWithContentDescription("Document actions").performClick(); compose.onNodeWithText("Extract Text").performClick()
            compose.onNodeWithText("Search within text").assertIsDisplayed()
            runBlocking { withTimeout(60000) { docs.dao.observeOcr(doc).first { rows -> rows.any { it.status=="complete" } } } }
            val text=runBlocking { docs.dao.ocr(page)!!.text }; assertTrue(text.contains("Newton",true))
            assertTrue("FTS must find the recognized page",runBlocking { docs.dao.searchOcr(searchExpression("newt")).first().any { it.pageId==page } })
            compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { docs.dao.save(docs.dao.ocr(page)!!.copy(status="failed")) }
            compose.waitUntil(15000) { compose.onAllNodesWithText("OCR couldn't be completed").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(60000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Copy All").performClick()
            compose.runOnUiThread { assertEquals(text,(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString()) }
            compose.onNodeWithText("Search within text").performTextInput("Newton")
            compose.onNodeWithText(text).assertIsDisplayed(); compose.onNodeWithContentDescription("Share extracted text").performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("android").depth(0)),15000) || device.wait(Until.hasObject(By.textContains("Sharing")),5000))
            device.pressBack()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Search documents").performTextInput("newt")
            compose.waitUntil(15000) { compose.onAllNodesWithText("Page 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Page 1").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Preview page 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Enhanced page preview").assertCountEquals(0)
            compose.onNodeWithContentDescription("Preview page 1").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Enhanced page preview").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Enhanced page preview").assertIsDisplayed()
        } finally { device.pressBack(); runBlocking { docs.purgeForTest(doc) } }
    }
    @Test fun acceptedPagesEnqueueIndependentlyAndTrashRetainsText() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888); val c=Canvas(image); c.drawColor(Color.WHITE)
        c.drawText("Printed invoice",30f,120f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=32f })
        val bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }; image.recycle()
        val doc=runBlocking { docs.create("Queued OCR acceptance") }
        try { runBlocking {
            val ids=List(2) {
                val draft=docs.stageScan(doc,ByteArrayInputStream(bytes)); docs.cropDraft(draft,dev.folio.scanner.processing.Geometry.full)
                docs.acceptDraft(draft,dev.folio.scanner.processing.Enhancement(),0)
            }
            assertEquals(2,docs.dao.pages(doc).size); ids.forEach { assertNotNull(docs.dao.ocr(it)) }
            docs.delete(doc); assertTrue(docs.dao.searchOcr(searchExpression("invoice")).first().none { it.documentId==doc })
            ids.forEach { assertNotNull(docs.dao.ocr(it)) }; docs.restore(setOf(doc))
            withTimeout(60000) { docs.dao.observeOcr(doc).first { it.size==2 && it.all { r -> r.status=="complete" } } }
            assertEquals(2,docs.dao.searchOcr(searchExpression("invoice")).first().count { it.documentId==doc })
        } } finally { runBlocking { docs.purgeForTest(doc) } }
    }
}

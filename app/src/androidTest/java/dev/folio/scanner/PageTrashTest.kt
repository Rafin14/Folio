package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.util.UUID

class PageTrashTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val repo get()=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).pdfs().documents
    private fun waitText(text:String) { compose.waitUntil(30000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun page(doc:String):String=runBlocking {
        val image=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes=ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() }; image.recycle()
        repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
    }
    @Test fun lifecycleRetainsAssetsMetadataOcrAndOrderingAcrossRepeatedCyclesAndParentTrash() {
        val doc=runBlocking { repo.create("Page lifecycle ${UUID.randomUUID().toString().take(6)}") }
        try { runBlocking {
            val a=page(doc); val b=page(doc); val c=page(doc)
            val original=repo.dao.page(b)!!
            repo.dao.save(OcrResult(b,"Private fixture text",1,status="complete"))
            repo.dao.save(Annotation(UUID.randomUUID().toString(),b,"test","{}"))
            repo.deletePages(doc,setOf(a,b)); assertEquals(listOf(c),repo.dao.pages(doc).map { it.id })
            assertEquals(2,repo.dao.trashedPages().count { it.documentId==doc })
            assertTrue(File(original.originalImageUri).isFile); assertTrue(File(original.thumbnailUri).isFile)
            assertEquals("Private fixture text",repo.dao.ocr(b)!!.text)
            assertFalse(repo.dao.driveDeletions().any { it.hash in setOf(a,b) })
            repo.recoverDeletes(); repo.restorePages(setOf(a,b))
            assertEquals(listOf(a,b,c),repo.dao.pages(doc).map { it.id })
            assertEquals(original,repo.dao.page(b)); assertEquals(1,repo.dao.annotations(b).size)
            repeat(2) { repo.deletePages(doc,setOf(b)); repo.restorePages(setOf(b)) }
            repo.deletePages(doc,setOf(b)); repo.delete(doc)
            try { repo.restorePages(setOf(b)); fail("Parent must be restored first") } catch(_:IllegalArgumentException) {}
            assertNotNull(repo.dao.page(b)!!.trashedAt)
            repo.restore(setOf(doc)); repo.reorder(doc,listOf(c,a)); repo.restorePages(setOf(b))
            assertEquals(listOf(c,b,a),repo.dao.pages(doc).map { it.id })
            repo.deletePages(doc,setOf(a,b,c)); assertEquals(0,repo.dao.document(doc)!!.pageCount)
            repo.restorePages(setOf(a,b,c)); assertEquals(listOf(c,b,a),repo.dao.pages(doc).map { it.id })
            repo.deletePages(doc,setOf(b)); val temp=File(inst.targetContext.cacheDir,"ocr-input-$b.source").apply { writeText("retained test input") }; repo.permanentlyDeletePages(setOf(b)); assertFalse(temp.exists())
            assertNull(repo.dao.page(b)); assertNull(repo.dao.ocr(b)); assertTrue(repo.dao.annotations(b).isEmpty())
            assertFalse(File(original.originalImageUri).exists()); assertNotNull(repo.dao.document(doc))
            repo.permanentlyDeletePages(setOf(b)) // Already absent is idempotent.
            repo.deletePages(doc,setOf(c)); val expired=repo.dao.page(c)!!
            repo.dao.save(expired.copy(trashedAt=System.currentTimeMillis()-61L*86_400_000))
            repo.cleanExpiredTrash(); assertNull(repo.dao.page(c)); assertNotNull(repo.dao.document(doc))
        } } finally { runBlocking { repo.purgeForTest(doc) } }
    }
    @Test fun recyclePageUiAndStandaloneBottomLeftPrintRemainSelectionAware() {
        val title="Page UI ${UUID.randomUUID().toString().take(6)}"; val doc=runBlocking { repo.create(title) }
        val device=UiDevice.getInstance(inst)
        try {
            val ids=List(3) { page(doc) }; waitText(title); compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed()
            val initial=compose.onNodeWithContentDescription("Print document").fetchSemanticsNode().boundsInRoot
            val add=compose.onNodeWithText("Add pages",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertEquals(initial.center.y,add.center.y,1f)
            for(count in listOf(0,1,2,3,0)) {
                if(compose.onAllNodesWithContentDescription("Cancel selection").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Cancel selection").performClick()
                if(count>0) { compose.onNodeWithContentDescription("Preview page 1").performTouchInput { longClick() }; (2..count).forEach { compose.onNodeWithContentDescription("Preview page $it").performClick() } }
                val bounds=compose.onNodeWithContentDescription("Print document").fetchSemanticsNode().boundsInRoot
                assertEquals(initial.left,bounds.left,.1f); assertEquals(initial.top,bounds.top,.1f)
                assertTrue(bounds.center.x<compose.activity.resources.displayMetrics.widthPixels/2f); assertTrue(bounds.center.y>compose.activity.resources.displayMetrics.heightPixels*.5f)
                compose.onNodeWithContentDescription("Document actions").performClick(); compose.onAllNodesWithText("Print selected pages").assertCountEquals(0); device.pressBack()
                compose.onNodeWithContentDescription("Print document").performClick(); waitText("Print document")
                compose.onNodeWithText("Open Android print preview").performClick()
                compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.printspooler")) }
                assertEquals(if(count==0) 3 else count,runBlocking { temporaryPdfAssets(doc).maxBy { it.createdAt }.pageCount })
                device.pressBack()
            }
            compose.onNodeWithContentDescription("Preview page 2").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("Delete selected pages").performClick(); compose.onNodeWithText("Move to Recycle Bin").performClick()
            compose.waitUntil(20000) { runBlocking { repo.dao.pages(doc).size==2 } }
            compose.onNodeWithContentDescription("Back").performClick(); compose.onNodeWithContentDescription("Recycle Bin").performClick()
            waitText("Page from $title"); compose.onNodeWithContentDescription("Recycled page thumbnail").assertExists()
            compose.activityRule.scenario.recreate(); waitText("Page from $title")
            compose.onNodeWithContentDescription("Restore page ${ids[1]}").performClick()
            compose.waitUntil(15000) { runBlocking { repo.dao.pages(doc).size==3 } }
            assertEquals(ids,runBlocking { repo.dao.pages(doc).map { it.id } })
            compose.onNodeWithContentDescription("Back").performClick(); waitText(title); compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription("Preview page 1").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Print document").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) } }
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed().performClick(); waitText("All selected pages (1)")
            compose.onNodeWithText("Close").performClick()
        } finally { device.pressBack(); runBlocking { repo.purgeForTest(doc) } }
    }
}

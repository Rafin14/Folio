package dev.folio.scanner

import android.graphics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.*
import dev.folio.scanner.ocr.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class SelectionSearchUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val repo get()=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).pdfs().documents
    private fun page(doc:String,color:Int=Color.WHITE):String=runBlocking {
        val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { bitmap.recycle() }
        repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
    }
    private fun waitText(text:String) { compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun capture(name:String) { val device=UiDevice.getInstance(inst); device.waitForIdle(); device.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"folio-selection-$name.png")) }
    @Test fun documentLongPressSelectionSurvivesLayoutAndSharesSeparateThenCombinedPdfs() {
        val a=runBlocking { repo.create("Selection first") }; val b=runBlocking { repo.create("Selection second") }
        val device=UiDevice.getInstance(inst)
        try {
            page(a,Color.RED); page(a,Color.GREEN); page(b,Color.BLUE)
            waitText("Selection first")
            compose.onNodeWithText("Selection first").performTouchInput { longClick() }
            compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onNodeWithText("Selection second").performClick(); compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithText("Selection second").performClick(); compose.onNodeWithText("1 selected").assertIsDisplayed()
            compose.onNodeWithText("Selection second").performClick(); compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Select all documents").performClick()
            val count=runBlocking { repo.dao.allDocuments().count { it.trashedAt==null && !it.deleting } }
            compose.onNodeWithText("$count selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Cancel document selection").performClick()
            compose.onNodeWithText("Selection first").performTouchInput { longClick() }; compose.onNodeWithText("Selection second").performClick()
            compose.onNodeWithText("Selection first").assert(isSelected())
            val toggle=if(compose.onAllNodesWithContentDescription("List view").fetchSemanticsNodes().isNotEmpty()) "List view" else "Grid view"
            compose.onNodeWithContentDescription(toggle).performClick()
            compose.onNodeWithText("Selection second").assert(isSelected()); capture("library-selection")
            compose.activityRule.scenario.recreate(); waitText("2 selected")
            compose.onNodeWithContentDescription("Share selected documents").performClick()
            compose.onNodeWithText("Share as multiple documents").performClick(); compose.onNodeWithText("Prepare and share").performScrollTo().performClick()
            compose.waitUntil(60000) { device.hasObject(By.textContains("Sharing")) || device.hasObject(By.text("Quick Share")) || device.hasObject(By.pkg("com.android.intentresolver")) }
            assertEquals(2,runBlocking { temporaryPdfAssets(a).last().pageCount }); assertEquals(1,runBlocking { temporaryPdfAssets(b).last().pageCount })
            capture("separate-sharesheet"); device.pressBack(); waitText("2 selected")
            compose.onNodeWithContentDescription("Share selected documents").performClick()
            compose.onNodeWithText("Share as one document").performClick(); compose.onNodeWithText("Prepare and share").performScrollTo().performClick()
            compose.waitUntil(60000) { device.hasObject(By.textContains("Sharing")) || device.hasObject(By.text("Quick Share")) || device.hasObject(By.pkg("com.android.intentresolver")) }
            val combined=runBlocking { temporaryPdfAssets(a).first { it.title=="Combined document" } }; assertEquals(3,combined.pageCount)
            val pdfs=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).pdfs()
            assertEquals(3,pdfs.engine.count(File(combined.path))); capture("combined-sharesheet")
        } finally { device.pressBack(); runBlocking { repo.purgeForTest(a); repo.purgeForTest(b) } }
    }
    @Test fun pageSelectionPrintsAndSharesOnlySubsetAndPermanentDeletionKeepsDocument() {
        val doc=runBlocking { repo.create("Selected page actions") }; val device=UiDevice.getInstance(inst)
        try {
            val ids=List(3) { page(doc,listOf(Color.RED,Color.GREEN,Color.BLUE)[it]) }
            waitText("Selected page actions"); compose.onNodeWithText("Selected page actions").performClick()
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("PDF workspace").assertCountEquals(0)
            compose.onNodeWithContentDescription("Preview page 1").performTouchInput { longClick() }
            compose.onNodeWithText("1 selected").assertIsDisplayed(); compose.onNodeWithContentDescription("Preview page 3").performScrollTo().performClick()
            compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Document actions").performClick(); compose.onNodeWithText("Select all pages").performClick()
            compose.onNodeWithText("3 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Preview page 2").performClick(); compose.onNodeWithText("2 selected").assertIsDisplayed()
            compose.onNodeWithContentDescription("Preview page 1").assert(isSelected()); capture("page-selection")
            compose.activityRule.scenario.recreate(); waitText("2 selected")
            compose.onNodeWithContentDescription("Print document").performClick()
            compose.onNodeWithText("All selected pages (2)").assertIsDisplayed()
            compose.onNodeWithText("Open Android print preview").performClick()
            compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.printspooler")) }
            compose.waitUntil(20000) { device.hasObject(By.text("1/2")) }; capture("selected-print")
            device.pressBack(); waitText("2 selected")
            compose.onNodeWithContentDescription("Share selected pages").performClick()
            compose.onNodeWithText("2 pages selected").assertExists()
            compose.onNodeWithText("Share as PDF").performScrollTo().performClick()
            compose.waitUntil(60000) { device.hasObject(By.textContains("Sharing")) || device.hasObject(By.text("Quick Share")) || device.hasObject(By.pkg("com.android.intentresolver")) }
            assertEquals(2,runBlocking { temporaryPdfAssets(doc).last().pageCount }); device.pressBack(); waitText("2 selected")
            compose.onNodeWithContentDescription("Delete selected pages").performClick(); compose.onNodeWithText("Move to Recycle Bin").performClick()
            compose.waitUntil(20000) { runBlocking { repo.dao.pages(doc).size==1 } }
            assertEquals(ids[1],runBlocking { repo.dao.pages(doc).single().id }); assertNull(runBlocking { repo.dao.document(doc)!!.trashedAt })
            compose.onNodeWithContentDescription("Print document").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick(); compose.onNodeWithContentDescription("Recycle Bin").performClick()
            compose.onNodeWithContentDescription("Restore page ${ids[0]}").assertExists(); compose.onNodeWithContentDescription("Restore page ${ids[2]}").assertExists()
        } finally { device.pressBack(); runBlocking { repo.purgeForTest(doc) } }
    }
    @Test fun ocrHighlightsActualTextNavigatesWordsPhrasesAndWrapsAfterRecreation() {
        val doc=runBlocking { repo.create("Repeated OCR search") }
        try {
            val id=page(doc)
            val lines=listOf("Invoice paid one")+List(30) { "Other printed line $it" }+listOf("INVOICE PAID two","Invoice paid three")
            val text=lines.joinToString("\n")
            runBlocking { val p=repo.dao.page(id)!!; val hash=dev.folio.scanner.backup.hashFile(File(p.originalImageUri))
                repo.dao.save(OcrResult(id,text,1,regionsJson(lines.map { TextRegion(it,listOf(.1,.1,.8,.1,.8,.2,.1,.2),.9) }),"complete",ocrRevision(p,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,p.width,p.height))
            }
            waitText("Repeated OCR search"); compose.onNodeWithText("Repeated OCR search").performClick()
            compose.onNodeWithContentDescription("Document actions").performClick(); compose.onNodeWithText("Extract Text").performClick()
            compose.onNodeWithText("Search within text").performTextInput("invoice")
            waitText("1 of 3"); compose.onNodeWithContentDescription("Previous match").performClick(); waitText("3 of 3")
            compose.onNodeWithContentDescription("Next match").performClick(); waitText("1 of 3")
            compose.onNodeWithText("invoice").performTextReplacement("invoice paid"); waitText("1 of 3")
            compose.onNodeWithContentDescription("Next match").performClick(); waitText("2 of 3")
            val annotated=compose.onNodeWithText(text).fetchSemanticsNode().config[SemanticsProperties.Text].single()
            assertEquals(text,annotated.text); assertEquals(4,annotated.spanStyles.size)
            compose.onNodeWithText("2 of 3").assertIsDisplayed()
            compose.onNodeWithContentDescription("Previous match").assertIsDisplayed()
            compose.onNodeWithContentDescription("Next match").assertIsDisplayed()
            capture("ocr-selected-match")
            compose.activityRule.scenario.recreate(); waitText("2 of 3")
            compose.onNodeWithText("invoice paid").performTextReplacement("absent"); waitText("0 matches")
            compose.onNodeWithContentDescription("Next match").assertIsNotEnabled(); compose.onNodeWithText(text).assertExists()
            compose.onNodeWithContentDescription("Copy All").assertIsEnabled()
        } finally { runBlocking { repo.purgeForTest(doc) } }
    }
}

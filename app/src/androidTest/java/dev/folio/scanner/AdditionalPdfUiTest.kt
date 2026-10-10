package dev.folio.scanner

import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.geom.PageSize
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AdditionalPdfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val utility get()=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).utility()
    private fun capture(name:String) {
        compose.waitForIdle();Thread.sleep(350)
        assertTrue(UiDevice.getInstance(inst).takeScreenshot(File(inst.targetContext.getExternalFilesDir(null),"additional-$name.png")))
    }
    @Test fun sharedPdfChooserFiltersAndShowsSelectionAcrossThemes() {
        val source=File(inst.targetContext.cacheDir,"chooser-${UUID.randomUUID()}.pdf")
        PdfDocument(PdfWriter(source)).use {it.addNewPage();it.addNewPage()}
        val pdf=runBlocking {utility.documents.importPdf(source,"Shared PDF chooser",utility.engine)}
        val scan=runBlocking {utility.documents.create("Excluded image document")}
        try {
            listOf("Light","Dark","AMOLED").forEach {theme ->
                var chosen=""
                compose.activityRule.scenario.onActivity {activity ->
                    val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(theme) {FolioTheme(theme) {ManagedPdfChooser(model,{}) {chosen=it}}}}
                }
                compose.waitUntil(15000) {compose.onAllNodesWithText("Shared PDF chooser").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Excluded image document").assertDoesNotExist()
                compose.onNodeWithText("Open PDF",substring=false).assertIsNotEnabled()
                compose.onNodeWithContentDescription("Open Folio document Shared PDF chooser").performClick().assertIsSelected()
                compose.onNodeWithText("1 PDF selected").assertIsDisplayed()
                capture("$theme-pdf-chooser-selected")
                compose.onNodeWithText("Open PDF",substring=false).performClick()
                assertEquals(pdf,chosen)
            }
        } finally {runBlocking {utility.documents.purgeForTest(pdf);utility.documents.purgeForTest(scan)};source.delete()}
    }
    @Test fun galleryOpensSelectedPageAndRetainsEditsAcrossThemes() {
        val source=File(inst.targetContext.cacheDir,"shared-images/gallery-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {pdf ->repeat(12) {pdf.addNewPage(if(it%2==0) PageSize.A4 else PageSize.A4.rotate())}}
        val original=source.readBytes()
        val id=runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(inst.targetContext,"dev.folio.scanner.files",source)))}
        try {
            val pages=utility.pages(id)
            val annotated=pages.last().copy(ink=listOf(dev.folio.scanner.pdf.PdfInk(android.graphics.Color.RED,.02f,listOf(dev.folio.scanner.pdf.InkPoint(.1f,.2f),dev.folio.scanner.pdf.InkPoint(.8f,.2f)))),notes=listOf(dev.folio.scanner.pdf.PdfNote("Gallery note",color=android.graphics.Color.RED)))
            utility.updatePages(id,pages.dropLast(1)+annotated)
            fun redPixels(bitmap:android.graphics.Bitmap):Int {
                val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                return pixels.count {android.graphics.Color.red(it)>150 && android.graphics.Color.green(it)<80 && android.graphics.Color.blue(it)<80}
            }
            val gallery=runBlocking {utility.preview(id,annotated,edge=480,includeAnnotations=true)}
            val background=runBlocking {utility.preview(id,annotated,edge=480)}
            try {assertTrue("Gallery includes ink and notes",redPixels(gallery)>100);assertEquals("Editor background avoids duplicate annotations",0,redPixels(background))}
            finally {gallery.recycle();background.recycle()}
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->
                    val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                    activity.setContent {key(theme) {FolioTheme(theme) {PdfWorkspace(null,model,{}, {},initialSession=id)}}}
                }
                compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page gallery").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithContentDescription("PDF page gallery").performScrollToNode(hasContentDescription("Open PDF page 12"))
                compose.onNodeWithContentDescription("Open PDF page 12").assertIsDisplayed().performClick()
                compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page 12 canvas").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("12 of 12").assertIsDisplayed()
                compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick()
                capture("$theme-gallery-selected-editor")
                compose.onNodeWithContentDescription("PDF editor menu").performClick()
                compose.onNodeWithText("Page gallery").performClick()
                compose.onNodeWithContentDescription("PDF page gallery").performScrollToNode(hasContentDescription("Open PDF page 12"))
                compose.onNodeWithContentDescription("Open PDF page 12").assertIsSelected()
                compose.waitUntil(15000) {compose.onAllNodes(hasContentDescription("Open PDF page 12") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Preview loaded")).fetchSemanticsNodes().isNotEmpty()}
                capture("$theme-gallery-selected")
                assertArrayEquals(original,source.readBytes())
            }
            assertEquals(270,utility.pages(id).last().rotation)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Continue Editing").performClick()
            compose.onNodeWithContentDescription("PDF page gallery").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard Changes and Leave").performClick()
            compose.waitUntil(15000) {!File(inst.targetContext.filesDir,"pdf-utility/$id").exists()}
        } finally {runBlocking {utility.discard(id)};source.delete()}
    }
    @Test fun homeManagedPdfUsesGalleryAndPdfEditorWithExistingSaveOptions() {
        val source=File(inst.targetContext.cacheDir,"home-gallery-${UUID.randomUUID()}.pdf")
        PdfDocument(PdfWriter(source)).use {it.addNewPage();it.addNewPage()}
        val original=source.readBytes()
        val title="Home gallery ${UUID.randomUUID()}"
        val doc=runBlocking {utility.documents.importPdf(source,title,utility.engine)}
        val before=utility.pending().toSet()
        try {
            compose.waitUntil(15000) {compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page gallery").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("2 pages · Folio PDF").assertIsDisplayed()
            capture("home-managed-pdf-gallery")
            compose.onNodeWithContentDescription("Open PDF page 2").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page 2 canvas").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("2 of 2").assertIsDisplayed()
            compose.onNodeWithContentDescription("Enhanced page preview").assertDoesNotExist()
            compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick()
            val id=utility.pending().single {it !in before}
            assertEquals(doc,utility.session(id).getString("folioDocument"))
            assertEquals(90,utility.pages(id)[1].rotation)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page 2 canvas").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithText("2 of 2").assertIsDisplayed()
            assertEquals(90,utility.pages(id)[1].rotation)
            compose.onNodeWithText("Export",substring=false).performClick()
            compose.onNodeWithText("Overwrite Existing PDF in Folio").assertExists()
            compose.onNodeWithText("Save as New PDF in Folio").assertExists()
            compose.onNodeWithText("Save to Storage").assertExists()
            capture("home-managed-pdf-save-options")
            assertArrayEquals(original,source.readBytes())
            compose.onNodeWithText("Discard operation").performScrollTo().performClick()
            compose.onNodeWithText("Discard Changes and Leave").performClick()
            compose.waitUntil(15000) {!File(inst.targetContext.filesDir,"pdf-utility/$id").exists()}
            assertTrue(runBlocking {utility.documents.dao.document(doc)!!.importedPdf})
        } finally {
            runBlocking {utility.pending().filter {it !in before}.forEach {utility.discard(it)};utility.documents.purgeForTest(doc)}
            source.delete()
        }
    }

    @Test fun discardCancelsPendingExportAndKeepsSourceAndCompletedFiles() {
        val source=File(inst.targetContext.cacheDir,"shared-images/discard-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {it.addNewPage()}
        val original=source.readBytes()
        val id=runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(inst.targetContext,"dev.folio.scanner.files",source)))}
        val completed=File(inst.targetContext.cacheDir,"completed-$id.pdf").apply {writeBytes(original)}
        val job=androidx.work.OneTimeWorkRequestBuilder<dev.folio.scanner.pdf.PdfUtilityWorker>()
            .setInitialDelay(1,java.util.concurrent.TimeUnit.HOURS).addTag("pdf-utility").addTag("utility-$id").build()
        try {
            utility.work.enqueue(job).result.get()
            utility.save(id,utility.session(id).put("workId",job.id.toString()).put("state","pending"))
            compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {FolioTheme("AMOLED") {PdfWorkspace(null,model,{}, {},initialSession=id)}}}
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Save and Export").assertIsNotEnabled()
            compose.onNodeWithText("Continue Processing").performClick()
            assertTrue(File(inst.targetContext.filesDir,"pdf-utility/$id").exists())
            compose.onNodeWithContentDescription("Back").performClick()
            capture("cancel-pending-export")
            compose.onNodeWithText("Discard and Leave").performClick()
            compose.waitUntil(15000) {!File(inst.targetContext.filesDir,"pdf-utility/$id").exists()}
            assertEquals(androidx.work.WorkInfo.State.CANCELLED,utility.work.getWorkInfoById(job.id).get()!!.state)
            assertArrayEquals(original,source.readBytes());assertArrayEquals(original,completed.readBytes())
        } finally {runBlocking {utility.cancelAndDiscard(id)};source.delete();completed.delete()}
    }

}

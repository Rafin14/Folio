package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.util.UUID

class GallerySourceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=inst.targetContext
    private val utility get()=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    private val repo get()=utility.documents
    private fun waitText(text:String) {compose.waitUntil(20000) {compose.onAllNodesWithText(text,substring=false).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    private fun waitDescription(text:String) {compose.waitUntil(20000) {compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    private fun capture(name:String) {compose.waitForIdle();assertTrue(UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"gallery-source-$name.png")))}
    private fun listMode() {if(compose.onAllNodesWithContentDescription("List view").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("List view").performClick()}
    private fun gridMode() {if(compose.onAllNodesWithContentDescription("Grid view").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Grid view").performClick()}
    private fun page(doc:String,index:Int)=runBlocking {
        val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.WHITE);Canvas(this).drawText("Sample page ${index+1}",40f,100f,Paint().apply {color=Color.BLACK;textSize=28f})}
        val bytes=ByteArrayOutputStream();try {bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)} finally {bitmap.recycle()}
        repo.importImage(doc,ByteArrayInputStream(bytes.toByteArray()),detectDocument=false)
    }
    @Test fun libraryListSpacingAndPageLayoutsPreserveSelectionAndOrderAfterRestart() {
        val a=runBlocking {repo.create("Gallery sample A")};val b=runBlocking {repo.create("Gallery sample B")}
        try {
            val ids=List(2) {page(a,it)};page(b,0)
            waitText("Gallery sample A");listMode()
            val first=compose.onNode(hasText("Gallery sample B") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            val second=compose.onNode(hasText("Gallery sample A") and hasClickAction()).fetchSemanticsNode().boundsInRoot
            assertTrue("Document cards have a visible gap",second.top-first.bottom>=8)
            capture("library-list")
            compose.onNodeWithText("Gallery sample A").performClick();waitDescription("Preview page 1");gridMode()
            compose.onNodeWithContentDescription("Preview page 1").performTouchInput {longClick()};waitText("1 selected")
            listMode();compose.onNodeWithContentDescription("Preview page 1").assertIsSelected();capture("document-list-selected")
            compose.onNodeWithContentDescription("Select page 2").performClick();waitText("2 selected")
            compose.activityRule.scenario.recreate();waitText("2 selected")
            compose.onNodeWithContentDescription("Grid view").assertIsDisplayed()
            compose.onNodeWithContentDescription("Cancel selection").performClick()
            compose.onNodeWithContentDescription("Actions for page 2").performClick();compose.onNodeWithText("Move earlier",substring=false).performClick()
            compose.waitUntil(15000) {runBlocking {repo.dao.pages(a).map {it.id}}==ids.reversed()}
            gridMode();compose.onNodeWithContentDescription("List view").assertIsDisplayed();capture("document-grid")
            assertEquals(ids.reversed(),runBlocking {repo.dao.pages(a).map {it.id}})
        } finally {runBlocking {repo.purgeForTest(a);repo.purgeForTest(b)}}
    }
    @Test fun pdfGalleryLayoutsAndListDragKeepSelectedPageAndSourceAcrossThemes() {
        val source=File(context.cacheDir,"shared-images/gallery-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {p ->repeat(3) {p.addNewPage()}}
        val original=source.readBytes()
        val id=runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source)))}
        try {
            listOf("Light","Dark","AMOLED").forEach {theme ->
                compose.activityRule.scenario.onActivity {activity ->val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {key(theme) {FolioTheme(theme) {PdfWorkspace(null,model,{}, {},initialSession=id)}}}}
                waitDescription("PDF page gallery");listMode()
                compose.waitUntil(15000) {compose.onAllNodes(hasContentDescription("Open PDF page 1") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Preview loaded")).fetchSemanticsNodes().isNotEmpty()}
                val one=compose.onNodeWithContentDescription("Open PDF page 1").fetchSemanticsNode().boundsInRoot
                val two=compose.onNodeWithContentDescription("Open PDF page 2").fetchSemanticsNode().boundsInRoot
                assertEquals(one.left,two.left,1f);assertTrue(two.top>one.bottom);capture("pdf-list-$theme")
                gridMode();capture("pdf-grid-$theme");listMode()
                compose.onNodeWithContentDescription("Open PDF page 2").performClick();waitDescription("PDF page 2 canvas")
                compose.onNodeWithText("2 of 3").assertIsDisplayed()
                compose.onNodeWithContentDescription("PDF editor menu").performClick();compose.onNodeWithText("Page gallery").performClick()
                waitDescription("PDF page gallery");compose.onNodeWithContentDescription("Grid view").assertIsDisplayed()
                compose.onNodeWithContentDescription("Open PDF page 2").assertIsSelected()
            }
            val before=utility.pages(id)
            val first=compose.onNodeWithContentDescription("Open PDF page 1").fetchSemanticsNode().boundsInRoot
            val second=compose.onNodeWithContentDescription("Open PDF page 2").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Open PDF page 1").performTouchInput {down(center)};Thread.sleep(700)
            compose.onNodeWithContentDescription("Open PDF page 1").performTouchInput {moveBy(second.center-first.center,700);up()}
            compose.waitUntil(15000) {utility.pages(id)[0].id==before[1].id}
            assertEquals(listOf(before[1].id,before[0].id,before[2].id),utility.pages(id).map {it.id})
            assertArrayEquals(original,source.readBytes());capture("pdf-list-reordered")
        } finally {runBlocking {utility.discard(id)};source.delete()}
    }
    @Test fun splitMergeAndEditOfferOnlyFolioPdfsWhileRasterKeepsDocuments() {
        val source=File(context.cacheDir,"tools-${UUID.randomUUID()}.pdf")
        PdfDocument(PdfWriter(source)).use {p ->repeat(2) {p.addNewPage()}}
        val pdf=runBlocking {repo.importPdf(source,"Source PDF",utility.engine)};val scan=runBlocking {repo.create("Source scan")}
        val sessions=mutableListOf<String>();val device=UiDevice.getInstance(inst)
        try {
            page(scan,0);waitText("PDF workspace");compose.onNodeWithText("PDF workspace").performClick()
            listOf("Split PDF" to "split","Merge PDF" to "merge","PDF to Image" to "raster").forEach {(label,kind) ->
                compose.onNodeWithText(label,substring=false).performScrollTo().performClick()
                compose.onNodeWithText("Select from Storage").assertIsDisplayed();compose.onNodeWithText("Folio Documents").assertIsDisplayed();capture("$kind-source")
                compose.onNodeWithText("Select from Storage").performClick()
                assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000));device.waitForIdle()
                repeat(4) {if(device.hasObject(By.pkg("com.google.android.documentsui"))) {device.pressBack();device.waitForIdle()}}
                waitText("Split PDF")
                compose.onNodeWithText(label,substring=false).performScrollTo().performClick();compose.onNodeWithText("Folio Documents").performClick();waitDescription("Open Folio document Source PDF")
                if(kind=="raster") compose.onNodeWithContentDescription("Open Folio document Source scan").assertIsDisplayed()
                else {compose.onNodeWithText("Folio PDFs").assertIsDisplayed();compose.onNodeWithContentDescription("Open Folio document Source scan").assertDoesNotExist()}
                capture("$kind-folio-picker")
                compose.onNodeWithContentDescription("Open Folio document Source PDF").performClick();val pending=utility.pending().toSet()
                compose.onNodeWithText(if(kind=="raster") "Use document" else "Open PDF").performClick()
                compose.waitUntil(30000) {utility.pending().any {it !in pending}}
                val id=utility.pending().single {it !in pending};sessions+=id
                assertEquals(kind,utility.session(id).getString("kind"));assertEquals(2,utility.session(id).getJSONArray("counts").getInt(0))
                if(kind=="merge") {
                    waitText("Add PDF");compose.onNodeWithText("Add PDF").performScrollTo().performClick();compose.onNodeWithText("Folio Documents").performClick();waitDescription("Open Folio document Source PDF")
                    compose.onNodeWithContentDescription("Open Folio document Source scan").assertDoesNotExist();capture("merge-add-folio-picker")
                    compose.onNodeWithContentDescription("Open Folio document Source PDF").performClick();compose.onNodeWithText("Open PDF").performClick()
                    compose.waitUntil(30000) {utility.session(id).getJSONArray("order").length()==2}
                    assertEquals(2,utility.session(id).getJSONArray("counts").getInt(1));capture("merge-pdf-sources")
                }
                compose.onNodeWithText("Discard operation").performScrollTo().performClick();waitText("Discard Changes and Leave")
                compose.onNodeWithText("Discard Changes and Leave").performClick();compose.waitUntil(20000) {!utility.folder(id,create=false).exists()};waitText("Split PDF")
            }
            compose.onNodeWithText("Edit PDF",substring=false).performScrollTo().performClick();compose.onNodeWithText("Folio Documents").performClick();waitDescription("Open Folio document Source PDF")
            compose.onNodeWithText("Folio PDFs").assertIsDisplayed();compose.onNodeWithContentDescription("Open Folio document Source scan").assertDoesNotExist();capture("edit-folio-picker")
            compose.onNodeWithContentDescription("Close Folio selection").performClick()
            assertEquals(2,runBlocking {repo.dao.pages(pdf).size});assertEquals(1,runBlocking {repo.dao.pages(scan).size})
        } finally {runBlocking {sessions.forEach {utility.discard(it)};repo.purgeForTest(pdf);repo.purgeForTest(scan)};source.delete()}
    }
}

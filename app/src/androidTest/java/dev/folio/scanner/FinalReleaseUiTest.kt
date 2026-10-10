package dev.folio.scanner

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FinalReleaseUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val utility get()=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    private fun screenshot(name:String) {
        compose.waitForIdle();Thread.sleep(400)
        val directory=File(context.getExternalFilesDir(null),"final-release-ui").apply {mkdirs()}
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(directory,"$name.png"))
    }
    private fun session():Pair<String,File> {
        val source=File(context.cacheDir,"shared-images/Release sample ${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {pdf -> repeat(3) {pdf.addNewPage(com.itextpdf.kernel.geom.PageSize(400f+it*100f,700f))}}
        return runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)),initialImport=true)} to source
    }
    @Test fun pinchPanDrawingAndResetPreservePageNavigation() {
        val (id,source)=session()
        try {
            compose.activityRule.scenario.onActivity {activity -> val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {FolioTheme("Dark") {PdfWorkspace(null,model,{},{},initialSession=id,importIntoFolio=true)}}}
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Open PDF page 1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Open PDF page 1").performClick()
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty()}
            val canvas=compose.onNodeWithContentDescription("PDF page 1 canvas")
            canvas.performTouchInput {pinch(start0=center-Offset(40f,0f),end0=center-Offset(150f,0f),start1=center+Offset(40f,0f),end1=center+Offset(150f,0f),durationMillis=600)}
            compose.waitForIdle()
            assertFalse(canvas.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("1.0"))
            canvas.performTouchInput {swipeLeft()}
            compose.onNodeWithText("1 of 3").assertExists()
            compose.onNodeWithText("Draw",substring=false).performClick()
            canvas.performTouchInput {swipe(center-Offset(25f,25f),center+Offset(25f,25f),400)}
            compose.waitUntil {utility.pages(id).first().ink.isNotEmpty()}
            screenshot("editor-zoom-drawing-dark")
            compose.onNodeWithContentDescription("Fit page").performClick()
            compose.onNodeWithText("Drawing",substring=false).performClick()
            canvas.performTouchInput {swipeLeft()}
            compose.waitUntil {compose.onAllNodesWithText("2 of 3").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(3,utility.pages(id).size)
        } finally {runBlocking {utility.discard(id)};source.delete()}
    }
    @Test fun galleryLongPressDragPersistsOrderWithoutChangingSource() {
        val (id,source)=session();val original=source.readBytes();val document=utility.session(id).getString("initialImportDocument")
        try {
            compose.activityRule.scenario.onActivity {activity -> val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {FolioTheme("Light") {PdfWorkspace(null,model,{},{},initialSession=id,importIntoFolio=true)}}}
            compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Open PDF page 3").fetchSemanticsNodes().isNotEmpty()}
            val first=compose.onNodeWithContentDescription("Open PDF page 1")
            val delta=compose.onNodeWithContentDescription("Open PDF page 3").fetchSemanticsNode().boundsInRoot.center-first.fetchSemanticsNode().boundsInRoot.center
            first.performTouchInput {down(center);advanceEventTime(800);moveTo(center+delta,delayMillis=500);up()}
            compose.waitUntil {utility.pages(id).map {it.source}==listOf(2,3,1)}
            screenshot("gallery-reordered-light")
            assertArrayEquals(original,source.readBytes())
            compose.onNodeWithText("Save to Folio").performClick()
            compose.waitUntil(30000) {runBlocking {utility.documents.dao.document(document)!=null}}
            assertEquals(listOf(2,3,1),utility.pages(id).map {it.source})
            val stored=runBlocking {utility.documents.dao.document(document)!!}
            utility.engine.read(File(utility.documents.directory(document),"pdf/${stored.pdfHash}.pdf")).use {pdf ->
                assertEquals(listOf(500f,600f,400f),(1..3).map {pdf.getPage(it).pageSize.width})
            }
            assertArrayEquals(original,source.readBytes())
            val managedId=runBlocking {utility.openManaged(document)}
            val storedBytes=File(utility.documents.directory(document),"pdf/${stored.pdfHash}.pdf").readBytes()
            try {
                compose.activityRule.scenario.onActivity {activity -> val model=ViewModelProvider(activity)[LibraryViewModel::class.java];activity.setContent {FolioTheme("Dark") {PdfWorkspace(null,model,{},{},initialSession=managedId)}}}
                compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("Open PDF page 3").fetchSemanticsNodes().isNotEmpty()}
                val managedFirst=compose.onNodeWithContentDescription("Open PDF page 1")
                val managedDelta=compose.onNodeWithContentDescription("Open PDF page 3").fetchSemanticsNode().boundsInRoot.center-managedFirst.fetchSemanticsNode().boundsInRoot.center
                managedFirst.performTouchInput {down(center);advanceEventTime(800);moveTo(center+managedDelta,delayMillis=500);up()}
                compose.waitUntil {utility.pages(managedId).map {it.source}==listOf(2,3,1)}
                screenshot("managed-gallery-reordered-dark")
                assertArrayEquals(storedBytes,File(utility.documents.directory(document),"pdf/${stored.pdfHash}.pdf").readBytes())
            } finally {runBlocking {utility.discard(managedId)}}
        } finally {runBlocking {utility.discard(id);utility.documents.purgeForTest(document)};source.delete()}
    }
    @Test fun sourceAndContextMenusFitAllThemes() {
        listOf("Light","Dark","AMOLED").forEach {theme ->
            compose.activityRule.scenario.onActivity {it.setContent {FolioTheme(theme) {PdfSourceDialog("Edit PDF",{},{},{})}}}
            compose.onNodeWithText("Select from Storage").assertIsDisplayed();compose.onNodeWithText("Folio Documents").assertIsDisplayed()
            screenshot("source-${theme.lowercase()}")
            compose.activityRule.scenario.onActivity {it.setContent {FolioTheme(theme) {FolioOverflowMenu(true,{},"OCR PDF") {listOf("OCR PDF","PDF to Word","Save text","Copy text","Export PDF","Share","Rename","Duplicate","Discard analysis session").forEachIndexed {i,label ->if(i>0) MenuSeparator();FolioMenuItem(label,{})}}}}}
            compose.onNodeWithContentDescription("Close menu").assertIsDisplayed();compose.onNodeWithText("Discard analysis session").assertIsDisplayed()
            screenshot("menu-${theme.lowercase()}")
        }
    }
}

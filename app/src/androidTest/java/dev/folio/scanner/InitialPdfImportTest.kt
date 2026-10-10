package dev.folio.scanner

import androidx.activity.compose.setContent
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

class InitialPdfImportTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun review(saveFirst:Boolean) {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/Initial import ${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {it.addNewPage();it.addNewPage()}
        val bytes=source.readBytes()
        val id=runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)),initialImport=true)}
        val document=utility.session(id).getString("initialImportDocument")
        var left=false
        try {
            compose.activityRule.scenario.onActivity {activity ->
                val model=ViewModelProvider(activity)[LibraryViewModel::class.java]
                activity.setContent {FolioTheme("AMOLED") {PdfWorkspace(null,model,{left=true},{},initialSession=id,importIntoFolio=true)}}
            }
            compose.waitUntil(15000) {compose.onAllNodesWithText("Save to Folio").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Save to Folio").assertIsEnabled()
            assertNull(runBlocking {utility.documents.dao.document(document)})
            if(saveFirst) {
                compose.onNodeWithText("Save to Folio").performClick()
                compose.waitUntil(30000) {runBlocking {utility.documents.dao.document(document)!=null}}
                compose.waitUntil(15000) {compose.onAllNodesWithText("Save to Folio").fetchSemanticsNodes().isEmpty()}
            }
            compose.waitForIdle();Thread.sleep(400)
            UiDevice.getInstance(inst).takeScreenshot(File(context.getExternalFilesDir(null),"import-gallery-${if(saveFirst) "saved" else "initial"}.png"))
            compose.onNodeWithContentDescription("Back").performClick()
            if(!saveFirst) {
                compose.onNodeWithText("Cancel Import?").assertIsDisplayed()
                compose.onNodeWithText("Keep Importing").performClick()
                compose.onNodeWithText("Save to Folio").assertExists()
                assertNull(runBlocking {utility.documents.dao.document(document)})
                compose.waitUntil {compose.onAllNodesWithText("Cancel Import?").fetchSemanticsNodes().isEmpty()}
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Discard Import").performClick()
                compose.waitUntil(30000) {left}
                assertNull(runBlocking {utility.documents.dao.document(document)})
                assertArrayEquals(bytes,source.readBytes())
                assertFalse(File(context.filesDir,"pdf-utility/$id").exists())
                return
            }
            compose.waitUntil(30000) {left}
            val doc=runBlocking {utility.documents.dao.document(document)!!}
            assertTrue(doc.importedPdf);assertEquals(2,doc.pageCount)
            assertEquals(listOf(0,1),runBlocking {utility.documents.dao.pages(document).map {it.position}})
            assertArrayEquals(bytes,File(utility.documents.directory(document),"pdf/${doc.pdfHash}.pdf").readBytes())
            assertArrayEquals(bytes,source.readBytes())
            assertFalse(File(context.filesDir,"pdf-utility/$id").exists())
            assertEquals(1,runBlocking {utility.documents.dao.allDocuments().count {it.id==document}})
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithText(doc.title).fetchSemanticsNodes().isNotEmpty()}
            compose.onNode(hasText(doc.title) and hasText("PDF",substring=false)).assertExists()
        } finally {runBlocking {utility.discard(id);utility.documents.purgeForTest(document)};source.delete()}
    }
    @Test fun backRequiresConfirmationAndDiscardNeverPublishes()=review(false)
    @Test fun saveThenBackPublishesOnlyOneDocument()=review(true)
    @Test fun saveThenEditUpdatesTheSameImportedDocument()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/Edited import ${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {it.addNewPage();it.addNewPage()}
        val original=source.readBytes()
        val id=utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)),initialImport=true)
        val document=utility.session(id).getString("initialImportDocument")
        try {
            assertEquals(document,utility.persistInitialImport(id))
            utility.updatePages(id,utility.pages(id).mapIndexed {index,page ->if(index==0) page.copy(rotation=90) else page})
            assertEquals(document,utility.persistInitialImport(id));assertEquals(document,utility.persistInitialImport(id))
            val stored=utility.documents.dao.document(document)!!
            assertEquals(1,utility.documents.dao.allDocuments().count {it.id==document})
            assertTrue(stored.importedPdf);assertEquals(2,stored.pageCount)
            utility.engine.read(File(utility.documents.directory(document),"pdf/${stored.pdfHash}.pdf")).use {assertEquals(90,it.getPage(1).rotation);assertEquals(0,it.getPage(2).rotation)}
            assertFalse(utility.session(id).optBoolean("dirty"));assertArrayEquals(original,source.readBytes())
        } finally {utility.discard(id);utility.documents.purgeForTest(document);source.delete()}
    }
    @Test fun interruptedInitialImportRecoversTheReservedDocumentOnlyOnce()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/Recovery import ${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {it.addNewPage();it.addNewPage()}
        val original=source.readBytes()
        val id=utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)),initialImport=true)
        val document=utility.session(id).getString("initialImportDocument")
        try {
            assertNull(utility.documents.dao.document(document))
            utility.recover()
            assertNull("An unsaved review must never be published by startup",utility.documents.dao.document(document))
            utility.save(id,utility.session(id).put("initialImportSaveRequested",true))
            utility.recover()
            assertTrue(utility.documents.dao.document(document)!!.importedPdf)
            // Simulate process death after the Room commit but before the session acknowledgment.
            utility.save(id,utility.session(id).apply {remove("initialImportSaved");remove("folioDocument")})
            utility.recover();utility.recover()
            assertEquals(1,utility.documents.dao.allDocuments().count {it.id==document})
            assertEquals(listOf(0,1),utility.documents.dao.pages(document).map {it.position})
            assertArrayEquals(original,source.readBytes())
            val doc=utility.documents.dao.document(document)!!
            assertArrayEquals(original,File(utility.documents.directory(document),"pdf/${doc.pdfHash}.pdf").readBytes())
        } finally {utility.discard(id);utility.documents.purgeForTest(document);source.delete()}
    }
}

package dev.folio.scanner

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID

class HomePdfImportUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun pickNative(name:String) {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000))
        fun clickFresh(selector:BySelector,timeout:Long=5000):Boolean {
            repeat(4) {
                device.waitForIdle()
                val node=device.wait(Until.findObject(selector),timeout) ?: return false
                try { node.click(); return true } catch(_:StaleObjectException) { Thread.sleep(250) }
            }
            return false
        }
        if(!device.hasObject(By.text(name))) {
            clickFresh(By.desc("Show roots"))
            assertTrue("Open Downloads",clickFresh(By.text("Downloads")))
        }
        if(!device.wait(Until.hasObject(By.text(name)),10000)) {
            UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(name))
        }
        assertTrue("Select $name",clickFresh(By.text(name)))
        val picker=By.pkg("com.google.android.documentsui")
        val deadline=android.os.SystemClock.uptimeMillis()+15000
        while(!device.wait(Until.gone(picker),500) && android.os.SystemClock.uptimeMillis()<deadline) {
            // Multiple-document selection uses a toolbar confirmation, sometimes without a text label.
            if(!clickFresh(By.res("com.google.android.documentsui","action_menu_select"),500)) {
                clickFresh(By.text(java.util.regex.Pattern.compile("(?i)open")),500)
            }
        }
        assertTrue("Device picker must return the selected file",device.wait(Until.gone(picker),5000))
    }
    @Test fun homeImageImportUsesExistingCropAndAcceptPipelineWithoutPdfBadge() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val title="Home image acceptance ${UUID.randomUUID()}";val name="$title.jpg"
        val bitmap=android.graphics.Bitmap.createBitmap(480,640,android.graphics.Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.WHITE)}
        val bytes=try {ByteArrayOutputStream().apply {bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,98,this)}.toByteArray()} finally {bitmap.recycle()}
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use {it.write(bytes)}
        try {
            compose.onNodeWithText("Import",substring=false).performClick();pickNative(name)
            compose.waitUntil(20000) {compose.onAllNodesWithText("Import images").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithText("Document name").performTextReplacement(title)
            compose.onNodeWithText("Save",substring=false).performClick()
            compose.waitUntil(30000) {compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Confirm crop").performClick()
            compose.waitUntil(30000) {compose.onAllNodes(hasText("Accept page") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Accept page").performClick()
            compose.waitUntil(30000) {runBlocking {utility.documents.dao.allDocuments().any {it.title==title && it.pageCount==1}}}
            val doc=runBlocking {utility.documents.dao.allDocuments().single {it.title==title}}
            assertFalse(doc.importedPdf);assertTrue(doc.pdfHash.isEmpty())
            val page=runBlocking {utility.documents.dao.pages(doc.id).single()}
            assertArrayEquals(bytes,java.io.File(page.originalImageUri).readBytes())
            context.contentResolver.openInputStream(uri)!!.use {assertArrayEquals(bytes,it.readBytes())}
        } finally {
            runBlocking {utility.documents.dao.allDocuments().filter {it.title==title}.forEach {utility.documents.purgeForTest(it.id)}}
            context.contentResolver.delete(uri,null,null)
        }
    }
    @Test fun homePickerReviewsBeforePersistentImportAndKeepsNativeSource() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val title="Home PDF acceptance ${UUID.randomUUID()}";val name="$title.pdf"
        val bytes=ByteArrayOutputStream().apply {PdfDocument(PdfWriter(this)).use {pdf ->repeat(2) {
            PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(PdfFontFactory.createFont(),18f).moveText(40.0,740.0).showText("Native import page ${it+1}").endText()
        }}}.toByteArray()
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");put(MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use {it.write(bytes)}
        val pending=utility.pending().toSet();var document=""
        fun waitText(text:String)=compose.waitUntil(30000) {compose.onAllNodesWithText(text,substring=false).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        try {
            compose.onNodeWithText("Import",substring=false).performClick()
            pickNative(name)
            waitText("Review PDF before import")
            assertNull(runBlocking {utility.documents.dao.allDocuments().firstOrNull {it.title==title}})
            compose.onNodeWithText("Review PDF",substring=false).performClick()
            compose.waitUntil(30000) {compose.onAllNodesWithContentDescription("Open PDF page 1").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Open PDF page 1").performClick()
            compose.waitUntil(30000) {compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty()}
            assertNull(runBlocking {utility.documents.dao.allDocuments().firstOrNull {it.title==title}})
            compose.onNodeWithText("Export",substring=false).performClick()
            waitText("Import PDF into Folio")
            compose.onNodeWithText("Output filename").performTextReplacement(title)
            compose.onNodeWithText("Import PDF into Folio").performScrollTo().performClick()
            compose.waitUntil(60000) {runBlocking {utility.documents.dao.allDocuments().any {it.title==title && it.importedPdf && it.pageCount==2}}}
            document=runBlocking {utility.documents.dao.allDocuments().single {it.title==title}.id}
            compose.waitUntil(30000) {compose.onAllNodesWithText("PDF saved in Folio").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Overwrite Existing PDF in Folio").assertExists()
            compose.onNodeWithText("Save as New PDF in Folio").assertExists()
            compose.onNodeWithText("Save to Storage").assertExists()
            compose.onNodeWithContentDescription("Back").performClick()
            waitText(title);compose.onNode(hasText(title) and hasText("PDF",substring=false)).assertExists()
            compose.activityRule.scenario.recreate();waitText(title);compose.onNode(hasText(title) and hasText("PDF",substring=false)).assertExists()
            assertTrue(runBlocking {utility.documents.dao.document(document)!!.importedPdf})
            context.contentResolver.openInputStream(uri)!!.use {assertArrayEquals(bytes,it.readBytes())}
            val stored=java.io.File(utility.documents.directory(document),"pdf/${runBlocking {utility.documents.dao.document(document)!!.pdfHash}}.pdf")
            utility.engine.read(stored).use {assertEquals(2,it.numberOfPages);assertTrue(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(it.getPage(2)).contains("Native import page 2"))}
        } finally {
            runBlocking {if(document.isNotEmpty()) utility.documents.purgeForTest(document);utility.pending().filter {it !in pending}.forEach {utility.discard(it)}}
            context.contentResolver.delete(uri,null,null)
        }
    }
}

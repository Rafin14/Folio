package dev.folio.scanner

import android.graphics.*
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class PdfAdditionalUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val utility get()=EntryPointAccessors.fromApplication(inst.targetContext,PdfWorkerDependencies::class.java).utility()
    private val repo get()=utility.documents
    private fun page(doc:String,color:Int):String=runBlocking {
        val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { bitmap.recycle() }
        repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
    }
    private fun waitEnabled(text:String)=compose.waitUntil(30000) { compose.onAllNodes(hasText(text,substring=false) and isEnabled()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    private fun waitText(text:String)=compose.waitUntil(20000) { compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    @Test fun chooserMixesWholeDocumentsAndOrderedPagesRenumbersAndRestores() {
        val a=runBlocking { repo.create("Chooser A ${System.nanoTime()}") }; val b=runBlocking { repo.create("Chooser B ${System.nanoTime()}") }
        val titleA=runBlocking { repo.dao.document(a)!!.title }; val titleB=runBlocking { repo.dao.document(b)!!.title }
        var session=""
        try {
            val ids=listOf(page(a,Color.RED),page(a,Color.GREEN),page(a,Color.BLUE)); val bIds=listOf(page(b,Color.YELLOW),page(b,Color.CYAN))
            val before=runBlocking { repo.dao.allDocuments() }; val pending=utility.pending().toSet()
            compose.onNodeWithText("PDF workspace").performClick(); compose.onNodeWithText("Generate PDF").performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Open Folio document $titleA").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Open Folio document $titleA").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Choose Folio Page 3").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Choose Folio Page 1").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Selection order 2").assertExists()
            compose.onNodeWithContentDescription("Choose Folio Page 3").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Selection order 1").assertExists(); compose.onAllNodesWithContentDescription("Selection order 2").assertCountEquals(0)
            compose.onNodeWithContentDescription("Choose Folio Page 3").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Back to Folio documents").performClick()
            compose.onNodeWithContentDescription("Select whole document $titleB").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Open Folio document $titleB").performClick(); compose.onNodeWithContentDescription("Selection order 3").assertExists(); compose.onNodeWithContentDescription("Selection order 4").assertExists()
            compose.activityRule.scenario.recreate(); waitText("4 pages selected")
            inst.uiAutomation.takeScreenshot().let { bitmap -> File(inst.targetContext.cacheDir,"pdf-additional-chooser.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
            compose.onNodeWithText("Continue",substring=false).performClick(); waitText("Output filename")
            session=utility.pending().single { it !in pending }
            val images=utility.session(session).getJSONArray("images"); assertEquals(4,images.length())
            listOf(Color.RED,Color.BLUE,Color.YELLOW,Color.CYAN).forEachIndexed { index,color -> val image=BitmapFactory.decodeFile(images.getString(index)); try { val pixel=image.getPixel(image.width/2,image.height/2); assertTrue(kotlin.math.abs(Color.red(pixel)-Color.red(color))<10); assertTrue(kotlin.math.abs(Color.green(pixel)-Color.green(color))<10); assertTrue(kotlin.math.abs(Color.blue(pixel)-Color.blue(color))<10) } finally { image.recycle() } }
            assertEquals(before,runBlocking { repo.dao.allDocuments() }); assertEquals(ids,runBlocking { repo.dao.pages(a).map { it.id } }); assertEquals(bIds,runBlocking { repo.dao.pages(b).map { it.id } })
        } finally { runBlocking { if(session.isNotEmpty()) utility.discard(session); repo.purgeForTest(a); repo.purgeForTest(b) } }
    }
    @Test fun pageEditorPrintDefaultsToThePageEnteredFromDocument() {
        val doc=runBlocking { repo.create("Print chosen page ${System.nanoTime()}") }; val device=UiDevice.getInstance(inst)
        try {
            page(doc,Color.RED); page(doc,Color.GREEN); val third=page(doc,Color.BLUE)
            val title=runBlocking { repo.dao.document(doc)!!.title }; waitText(title); compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription("Preview page 3").performScrollTo().performClick(); waitText("Edit page")
            compose.onNodeWithContentDescription("Print document").performClick(); waitText("All selected pages (1)")
            compose.onAllNodesWithText("All pages (3)").assertCountEquals(0)
            compose.onNodeWithText("Open Android print preview").performClick()
            compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.printspooler")) }
            val asset=runBlocking { temporaryPdfAssets(doc).single() }; assertEquals(1,asset.pageCount)
            android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(File(asset.path),android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> assertEquals(1,renderer.pageCount); renderer.openPage(0).use { page -> val bitmap=Bitmap.createBitmap(60,80,Bitmap.Config.ARGB_8888); try { page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertTrue(Color.blue(bitmap.getPixel(30,40))>240); assertTrue(Color.red(bitmap.getPixel(30,40))<15) } finally { bitmap.recycle() } } }
            assertEquals(3,runBlocking { repo.dao.pages(doc).size }); assertEquals(third,runBlocking { repo.dao.pages(doc).last().id })
        } finally { device.pressBack(); runBlocking { repo.purgeForTest(doc) } }
    }
    @Test fun everyPdfToolOpensNativeDocumentsAndCancelKeepsLibraryUnchanged() {
        val device=UiDevice.getInstance(inst); val before=runBlocking { repo.dao.allDocuments() }
        compose.onNodeWithText("PDF workspace").performClick()
        listOf("Split PDF","Merge PDF","PDF to Image","Edit PDF").forEach { tool ->
            compose.onNodeWithText(tool,substring=false).performScrollTo().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000))
            device.waitForIdle()
            assertTrue("$tool opens Documents on this supported platform",device.wait(Until.hasObject(By.text("Documents")),10000))
            repeat(4) { if(device.hasObject(By.pkg("com.google.android.documentsui"))) { device.pressBack(); device.waitForIdle() } }
            waitText("Files in. Files out.")
        }
        assertEquals(before,runBlocking { repo.dao.allDocuments() })
    }
    @Test fun nativeMergeStartsWithOneAddsRemovesReordersAndExportsInRememberedFolder() {
        val context=inst.targetContext; val resolver=context.contentResolver; val device=UiDevice.getInstance(inst)
        inst.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root"); val root=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
        val label="Merge fixture ${java.util.UUID.randomUUID()}"; val folder=DocumentsContract.createDocument(resolver,root,DocumentsContract.Document.MIME_TYPE_DIR,label)!!
        val prefs=context.getSharedPreferences("pdf-utility-destinations",0); val previous=prefs.getString("pdf",null)
        assertTrue(utility.rememberDestination("pdf",tree)); var session=""; var output:Uri?=null
        fun pdf(name:String,count:Int):Uri { val uri=DocumentsContract.createDocument(resolver,folder,"application/pdf",name)!!; resolver.openOutputStream(uri,"w")!!.use { out -> com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(out)).use { p -> repeat(count) { p.addNewPage() } } }; return uri }
        fun choose(name:String) {
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000)); device.waitForIdle()
            device.findObject(By.desc("Show roots"))?.click(); device.waitForIdle(); device.wait(Until.hasObject(By.text("Folio test files")),10000); device.findObject(By.text("Folio test files")).click(); device.waitForIdle()
            device.wait(Until.hasObject(By.text(label)),10000); device.findObject(By.text(label)).click(); device.waitForIdle(); device.wait(Until.hasObject(By.text(name)),10000); device.findObject(By.text(name)).click()
            waitText("Open selected PDF"); waitEnabled("Open"); compose.onNodeWithText("Open",substring=false).performClick()
        }
        try {
            val a=pdf("Merge A.pdf",1); val b=pdf("Merge B.pdf",2); val before=runBlocking { repo.dao.allDocuments() }; val pending=utility.pending().toSet()
            compose.onNodeWithText("PDF workspace").performClick(); compose.onNodeWithText("Merge PDF").performClick(); choose("Merge A.pdf")
            waitEnabled("Add PDF"); session=utility.pending().single { it !in pending }; assertEquals(1,utility.session(session).getJSONArray("order").length())
            waitEnabled("Add PDF"); compose.onNodeWithText("Add PDF").performScrollTo().performClick(); choose("Merge B.pdf"); try { compose.waitUntil(30000) { utility.session(session).getJSONArray("order").length()==2 } } catch(failure:Throwable) {
                val error=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.folio.scanner.ui.LibraryViewModel::class.java].error.value
                android.util.Log.e("Folio PDF UI test","Append did not finish: error=$error, state=${utility.session(session)}, new sessions=${utility.pending().filter { it !in pending }}")
                inst.uiAutomation.takeScreenshot().let { bitmap -> File(context.cacheDir,"native-merge-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
                throw failure
            }
            compose.onNodeWithContentDescription("Remove PDF 2").performClick(); assertEquals(1,utility.session(session).getJSONArray("order").length())
            waitEnabled("Add PDF"); compose.onNodeWithText("Add PDF").performScrollTo().performClick(); choose("Merge B.pdf"); compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Drag to reorder 2").fetchSemanticsNodes().isNotEmpty() }
            val first=compose.onNodeWithContentDescription("Drag to reorder 0").fetchSemanticsNode().boundsInRoot; val last=compose.onNodeWithContentDescription("Drag to reorder 2").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Drag to reorder 0").performTouchInput { down(center); moveBy(last.center-first.center,800); up() }
            compose.onNodeWithText("Output filename").assertTextContains("Merge B Merge A merged")
            compose.onNodeWithText("Export PDF").performScrollTo().performClick(); waitText("Export verified in device storage")
            output=Uri.parse(utility.session(session).getString("destination")); android.graphics.pdf.PdfRenderer(resolver.openFileDescriptor(output!!,"r")!!).use { assertEquals(3,it.pageCount) }
            assertEquals(before,runBlocking { repo.dao.allDocuments() }); assertTrue(resolver.openInputStream(a)!!.use { it.readBytes().size }>100); assertTrue(resolver.openInputStream(b)!!.use { it.readBytes().size }>100)
        } finally { output?.let { DocumentsContract.deleteDocument(resolver,it) }; DocumentsContract.deleteDocument(resolver,folder); prefs.edit().apply { if(previous==null) remove("pdf") else putString("pdf",previous) }.commit(); runBlocking { if(session.isNotEmpty()) utility.discard(session) } }
    }
}

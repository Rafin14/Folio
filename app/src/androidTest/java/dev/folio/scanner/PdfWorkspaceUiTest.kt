package dev.folio.scanner

import android.graphics.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class PdfWorkspaceUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun missingInitialSessionReturnsToToolsWithoutAStaleTitleRead() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/missing-session-${System.nanoTime()}.pdf").apply {parentFile!!.mkdirs()}
        com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(source)).use {it.addNewPage()}
        val original=source.readBytes()
        val id=runBlocking {utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source)))}
        try {
            runBlocking {utility.discard(id)}
            compose.activityRule.scenario.onActivity {activity ->val model=androidx.lifecycle.ViewModelProvider(activity)[dev.folio.scanner.ui.LibraryViewModel::class.java]
                activity.setContent {dev.folio.scanner.ui.FolioTheme("AMOLED") {dev.folio.scanner.ui.PdfWorkspace(null,model,{}, {},initialSession=id)}}
            }
            compose.waitUntil(15000) {compose.onAllNodesWithText("Split PDF").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Split PDF").assertIsDisplayed();assertArrayEquals(original,source.readBytes())
            assertFalse(utility.folder(id,create=false).exists())
        } finally {runBlocking {utility.discard(id)};source.delete()}
    }
    private fun openSession(id:String) {
        compose.activityRule.scenario.onActivity {activity ->
            val model=androidx.lifecycle.ViewModelProvider(activity)[dev.folio.scanner.ui.LibraryViewModel::class.java]
            activity.setContent {dev.folio.scanner.ui.FolioTheme("System") {dev.folio.scanner.ui.PdfWorkspace(null,model,{}, {},initialSession=id)}}
        }
        compose.waitUntil(15000) {
            compose.onAllNodesWithContentDescription("Open PDF page 1").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription("Close PDF editor").fetchSemanticsNodes().isNotEmpty()
        }
        // Restored saveable state can already be displaying the editor.
        if(compose.onAllNodesWithContentDescription("Open PDF page 1").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithContentDescription("Open PDF page 1").performClick()
    }
    @Test fun unsavedDialogActionsFitWithSpacingAndCancelPreservesSession() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"shared-images/dialog-layout-${System.nanoTime()}.pdf").apply { parentFile!!.mkdirs() }
        com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(source)).use { it.addNewPage() }
        val id=runBlocking { utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))) }
        try {
            openSession(id)
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Close PDF editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick()
            val pages=utility.pages(id)
            compose.onNodeWithContentDescription("Close PDF editor").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Leave with unsaved changes?").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            val dialog=compose.onNode(isRoot() and hasAnyDescendant(hasText("Leave with unsaved changes?"))).getUnclippedBoundsInRoot()
            var previousBottom=compose.onNodeWithText("Leave with unsaved changes?").getUnclippedBoundsInRoot().bottom
            listOf("Save and Export","Discard Changes and Leave","Continue Editing").forEach { label ->
                val button=compose.onNode(hasClickAction() and hasText(label,substring=false)).assertIsDisplayed().assertIsEnabled()
                val bounds=button.getUnclippedBoundsInRoot()
                assertTrue("$label fully inside dialog",bounds.left>=dialog.left && bounds.right<=dialog.right && bounds.top>=dialog.top && bounds.bottom<=dialog.bottom)
                assertTrue("$label separated from preceding content",bounds.top>previousBottom)
                previousBottom=bounds.bottom
            }
            assertTrue("Bottom padding below Cancel",dialog.bottom>previousBottom)
            compose.waitForIdle(); Thread.sleep(350)
            instrumentation.uiAutomation.takeScreenshot().let { image ->
                File(context.cacheDir,"pdf-unsaved-dialog.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
            }
            compose.onNodeWithText("Continue Editing",substring=false).performClick()
            compose.onNodeWithContentDescription("Close PDF editor").assertIsDisplayed()
            assertEquals(pages,utility.pages(id))
        } finally { runBlocking { utility.discard(id) }; source.delete() }
    }
    @Test fun folioDocumentDragPersistsOrderWithoutBreakingLongPressSelection() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility().documents
        val title="Drag order ${System.nanoTime()}"
        val doc=runBlocking { repo.create(title) }
        try {
            val ids=runBlocking { List(2) { index ->
                val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(if(index==0) Color.RED else Color.BLUE) }
                val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
                repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
            } }
            compose.waitUntil(20000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).performClick()
            compose.waitUntil(20000) { compose.onAllNodesWithContentDescription("Preview page 1").fetchSemanticsNodes().isNotEmpty() }
            val first=compose.onNodeWithContentDescription("Preview page 1",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val second=compose.onNodeWithContentDescription("Preview page 2",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Preview page 1",useUnmergedTree=true).performTouchInput { down(center) }
            Thread.sleep(700)
            compose.onNodeWithContentDescription("Preview page 1",useUnmergedTree=true).performTouchInput { val delta=second.center-first.center; moveBy(delta*.1f,100); moveBy(delta*.8f,500); moveBy(delta*.1f,100); up() }
            compose.waitUntil(15000) { runBlocking { repo.dao.pages(doc).map { it.id } }==ids.reversed() }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Preview page 1").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            assertEquals(ids.reversed(),runBlocking { repo.dao.pages(doc).map { it.id } })
            compose.onNodeWithContentDescription("Preview page 1").performTouchInput { longClick() }
            compose.onNodeWithText("1 selected").assertIsDisplayed()
        } finally { runBlocking { repo.purgeForTest(doc) } }
    }
    @Test fun editedPdfExportsToRememberedFolderAndCompletionSurvivesRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        val title="Edited utility ${java.util.UUID.randomUUID().toString().take(8)}"
        val source=File(context.cacheDir,"shared-images/$title.pdf").apply { parentFile!!.mkdirs() }
        com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(source)).use { it.addNewPage() }
        val original=source.readBytes(); val before=runBlocking { utility.documents.dao.allDocuments() }
        val id=runBlocking { utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))) }
        var destination:android.net.Uri?=null
        val prefs=context.getSharedPreferences("pdf-utility-destinations",0); val previous=prefs.getString("pdf",null)
        instrumentation.uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        assertTrue(utility.rememberDestination("pdf",android.provider.DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")))
        try {
            openSession(id)
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Draw",substring=false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("PDF page 1 canvas").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.35f,height*.3f),androidx.compose.ui.geometry.Offset(width*.65f,height*.6f),700) }
            device.pressBack()
            compose.onNodeWithText("Export",substring=false).performClick()
            compose.onNodeWithText("Output filename").performTextReplacement(title)
            compose.onNodeWithText("Export PDF").performScrollTo().performClick()
            compose.waitUntil(90000) { compose.onAllNodesWithText("Export verified in device storage").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            destination=android.net.Uri.parse(utility.session(id).getString("destination"))
            android.graphics.pdf.PdfRenderer(context.contentResolver.openFileDescriptor(destination!!,"r")!!).use { assertEquals(1,it.pageCount) }
            val exported=File(context.cacheDir,"edited-proof.pdf")
            context.contentResolver.openInputStream(destination!!)!!.use { input -> exported.outputStream().use { input.copyTo(it) } }
            try { utility.engine.read(exported).use { assertTrue(it.getPage(1).contentBytes.toString(Charsets.ISO_8859_1).contains(" RG")) } } finally { exported.delete() }
            assertArrayEquals(original,source.readBytes()); assertEquals(before,runBlocking { utility.documents.dao.allDocuments() })
            compose.activityRule.scenario.recreate()
            compose.waitUntil(15000) { compose.onAllNodesWithText("PDF workspace").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithText("PDF workspace").performClick()
            compose.onNodeWithText("Edit PDF").assertIsDisplayed()
            compose.onAllNodesWithText("Resume edit operation").assertCountEquals(0)
            assertEquals("complete",utility.session(id).getString("state"))
            android.graphics.pdf.PdfRenderer(context.contentResolver.openFileDescriptor(destination!!,"r")!!).use {assertEquals(1,it.pageCount)}
        } finally { destination?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver,it) }; prefs.edit().apply { if(previous==null) remove("pdf") else putString("pdf",previous) }.commit(); runBlocking { utility.discard(id) }; source.delete() }
    }
    @Test fun lightDarkAmoledAndSystemWorkspaceRemainReadableAndAligned() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=context.getSharedPreferences("appearance",0)
        val previous=preferences.getString("theme","System")
        try {
            listOf("Light","Dark","AMOLED","System").forEach { mode ->
                compose.onNodeWithContentDescription("Settings").performClick()
                compose.onNodeWithText(mode,substring=false).performScrollTo().performClick()
                compose.onNodeWithContentDescription("Back").performClick()
                compose.waitForIdle(); Thread.sleep(400) // Allow the native compositor to publish the settled navigation frame.
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                    File(context.cacheDir,"workspace-$mode-home.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                }
                compose.onNodeWithText("PDF workspace").performClick()
                compose.onNodeWithText("Edit PDF").assertIsDisplayed()
                compose.waitForIdle(); Thread.sleep(400) // Allow the native compositor to publish the settled navigation frame.
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                    File(context.cacheDir,"workspace-$mode-tools.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                }
                compose.onNodeWithText("Generate PDF").performClick()
                compose.onNodeWithContentDescription("Close Folio selection").assertIsDisplayed()
                compose.waitForIdle(); Thread.sleep(350)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                    File(context.cacheDir,"workspace-$mode-chooser.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
                }
                compose.onNodeWithContentDescription("Close Folio selection").performClick()
                compose.onNodeWithContentDescription("Back").performClick()
            }
        } finally { preferences.edit().putString("theme",previous).commit(); compose.activityRule.scenario.recreate() }
    }

    @Test fun dedicatedEditorDrawBackSwipeReorderRotationDeleteAndUnsavedCancel() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val before=runBlocking { utility.documents.dao.allDocuments() }
        val source=File(context.cacheDir,"shared-images/editor-ui-${System.nanoTime()}.pdf").apply { parentFile!!.mkdirs() }
        com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(source)).use { pdf -> repeat(3) { pdf.addNewPage() } }
        val id=runBlocking { utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))) }
        try {
            compose.onNodeWithText("PDF workspace").performClick()
            listOf("Split PDF","Merge PDF","Image to PDF","PDF to Image","Edit PDF","Generate PDF").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            compose.onAllNodesWithText("Saved PDFs").assertCountEquals(0); compose.onAllNodesWithText("Import PDF").assertCountEquals(0)
            openSession(id)
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithText("Note",substring=false).assertCountEquals(0)
            compose.onNodeWithText("Draw",substring=false).performScrollTo().performClick(); compose.onNodeWithText("Bold").performScrollTo().performClick(); compose.onNodeWithContentDescription("Drawing color 4").performScrollTo().performClick()
            compose.onNodeWithContentDescription("PDF page 1 canvas").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width*.35f,height*.3f),androidx.compose.ui.geometry.Offset(width*.65f,height*.6f),700) }
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack(); compose.onAllNodesWithText("Leave with unsaved changes?").assertCountEquals(0); compose.onNodeWithText("Draw",substring=false).assertExists()
            compose.waitForIdle(); Thread.sleep(350)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { image ->
                File(context.cacheDir,"pdf-utility-editor.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
            }
            compose.onNodeWithContentDescription("PDF page 1 canvas").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { compose.onAllNodesWithText("2 of 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rotate",substring=false).performScrollTo().performClick()
            compose.onNodeWithContentDescription("Arrange PDF pages").performClick()
            val original=utility.pages(id)
            val first=compose.onNodeWithContentDescription("Drag to reorder ${original[0].id}").fetchSemanticsNode().boundsInRoot
            val third=compose.onNodeWithContentDescription("Drag to reorder ${original[2].id}").fetchSemanticsNode().boundsInRoot
            compose.onNodeWithContentDescription("Drag to reorder ${original[0].id}").performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f,third.center.y-first.center.y),800); up() }
            compose.onNodeWithText("Done",substring=false).performClick()
            assertEquals(original[0].id,utility.pages(id).last().id)
            compose.onNodeWithText("Delete",substring=false).performScrollTo().performClick(); compose.onNodeWithText("Cancel",substring=false).performClick()
            compose.onNodeWithContentDescription("Close PDF editor").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Leave with unsaved changes?").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }; compose.onNodeWithText("Leave with unsaved changes?").assertIsDisplayed(); compose.onNodeWithText("Continue Editing",substring=false).performClick()
            compose.onNodeWithContentDescription("Close PDF editor").performClick(); compose.onNodeWithText("Save and Export").performClick()
            compose.onNodeWithText("Return to PDF editor").performClick()
            val state=utility.pages(id); assertTrue("Ink preserved",state.any { it.ink.isNotEmpty() }); assertTrue("Rotation preserved",state.any { it.rotation==90 })
            compose.activityRule.scenario.recreate()
            // This fixture installs a standalone editor, outside MainActivity's navigation tree.
            // Reattach its durable session; actual navigation recreation is tested from Home.
            openSession(id)
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Close PDF editor").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            assertEquals(state,utility.pages(id))
            compose.onNodeWithContentDescription("Close PDF editor").performClick(); compose.onNodeWithText("Discard Changes and Leave").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("PDF workspace").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before,runBlocking { utility.documents.dao.allDocuments() })
        } finally { runBlocking { utility.discard(id) }; source.delete() }
    }
    @Test fun scannerAndFolioReplacementCopyOnlyTheirChosenPdfPage() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant dev.folio.scanner android.permission.CAMERA").close()
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val doc=runBlocking { val d=utility.documents.create("Replacement source ${System.nanoTime()}"); val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }; val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }; val p=utility.documents.importImage(d,ByteArrayInputStream(bytes),detectDocument=false); utility.documents.renamePage(p,"Replacement copy fixture"); d }
        val source=File(context.cacheDir,"shared-images/retake-ui-${System.nanoTime()}.pdf").apply { parentFile!!.mkdirs() }
        com.itextpdf.kernel.pdf.PdfDocument(com.itextpdf.kernel.pdf.PdfWriter(source)).use { pdf -> repeat(3) { pdf.addNewPage() } }
        val sourceBytes=source.readBytes()
        val id=runBlocking { utility.open("edit",listOf(androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",source))) }
        val originals=utility.pages(id); val before=runBlocking { utility.documents.dao.allDocuments() }; val originalPages=runBlocking { utility.documents.dao.pages(doc) }; val imageBytes=File(originalPages.single().originalImageUri).readBytes()
        var externalImage:android.net.Uri?=null
        try {
            openSession(id)
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Replace",substring=false).performScrollTo().performClick(); compose.onNodeWithText("Choose Folio page").performClick()
            compose.onNodeWithContentDescription("Open Folio document "+runBlocking { utility.documents.dao.document(doc)!!.title }).performClick(); compose.onNodeWithContentDescription("Choose Folio Replacement copy fixture").performClick(); compose.onNodeWithText("Use selected page").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Confirm crop").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Use page").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use page").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("PDF page 1 canvas").fetchSemanticsNodes().isNotEmpty() && utility.pages(id)[0].replacement.isNotEmpty() }
            assertEquals(originals.drop(1),utility.pages(id).drop(1))
            compose.onNodeWithContentDescription("PDF page 1 canvas").performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { compose.onAllNodesWithText("2 of 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Replace",substring=false).performScrollTo().performClick(); compose.onNodeWithText("Use Folio scanner").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Live camera preview").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(30000) {
                var streaming=false
                compose.activityRule.scenario.onActivity { activity ->
                    fun ready(view:android.view.View):Boolean = when(view) {
                        is androidx.camera.view.PreviewView -> view.previewStreamState.value==androidx.camera.view.PreviewView.StreamState.STREAMING
                        is android.view.ViewGroup -> (0 until view.childCount).any {ready(view.getChildAt(it))}
                        else -> false
                    }
                    streaming=ready(activity.window.decorView)
                }
                streaming
            }
            compose.onNodeWithContentDescription("Capture page").assertIsEnabled()
            compose.onNodeWithContentDescription("Capture page").performClick()
            try { compose.waitUntil(45000) { compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes().isNotEmpty() } }
            catch(failure:Throwable) {
                val model=androidx.lifecycle.ViewModelProvider(compose.activity)[dev.folio.scanner.ui.LibraryViewModel::class.java]
                throw AssertionError("Scanner replacement did not open: busy=${model.busy.value}, error=${model.error.value}",failure)
            }
            compose.onNodeWithText("Confirm crop").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Use page").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use page").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("2 of 3").fetchSemanticsNodes().isNotEmpty() && utility.pages(id)[1].replacement.isNotEmpty() }
            assertEquals(originals[2],utility.pages(id)[2]); assertEquals(before,runBlocking { utility.documents.dao.allDocuments() }); assertEquals(originalPages,runBlocking { utility.documents.dao.pages(doc) }); assertArrayEquals(imageBytes,File(originalPages.single().originalImageUri).readBytes()); assertArrayEquals(sourceBytes,source.readBytes())
            val imageName="replacement-${java.util.UUID.randomUUID()}.jpg"
            externalImage=context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,imageName); put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/jpeg"); put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
            })!!
            context.contentResolver.openOutputStream(externalImage!!)!!.use { it.write(imageBytes) }
            val preserved=utility.pages(id).take(2)
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("PDF page 2 canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("PDF page 2 canvas").assertIsDisplayed().performTouchInput { swipeLeft() }
            compose.waitUntil(10000) { compose.onAllNodesWithText("3 of 3").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Replace",substring=false).performScrollTo().performClick(); compose.onNodeWithText("Choose device image or PDF").performClick()
            val device=androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            var image=device.wait(Until.findObject(By.text(imageName)),5000) ?: device.wait(Until.findObject(By.descStartsWith("$imageName,")),5000)
            if(image==null) { device.findObject(By.desc("Show roots"))?.click(); device.wait(Until.findObject(By.text("Downloads")),10000)?.click(); image=device.wait(Until.findObject(By.text(imageName)),5000) ?: device.wait(Until.findObject(By.descStartsWith("$imageName,")),5000) }
            assertNotNull(image); image!!.click()
            compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("Crop corners").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithText("Confirm crop").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Use page").filter(isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use page").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("3 of 3").fetchSemanticsNodes().isNotEmpty() && utility.pages(id)[2].replacement.isNotEmpty() }
            assertEquals(preserved,utility.pages(id).take(2)); assertEquals(before,runBlocking { utility.documents.dao.allDocuments() }); assertEquals(originalPages,runBlocking { utility.documents.dao.pages(doc) }); assertArrayEquals(sourceBytes,source.readBytes())
            compose.onNodeWithContentDescription("Close PDF editor").performClick(); compose.onNodeWithText("Discard Changes and Leave").performClick()
        } finally { externalImage?.let { context.contentResolver.delete(it,null,null) }; runBlocking { utility.discard(id); utility.documents.purgeForTest(doc) }; source.delete() }
    }

}


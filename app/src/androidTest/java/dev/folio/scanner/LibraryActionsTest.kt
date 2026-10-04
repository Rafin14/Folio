package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.Enhancement
import dev.folio.scanner.ui.LibraryViewModel
import dev.folio.scanner.ui.imageShareIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*
import java.util.UUID

class LibraryActionsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun repo(): DocumentRepository {
        lateinit var model: LibraryViewModel
        compose.runOnUiThread { model=ViewModelProvider(compose.activity)[LibraryViewModel::class.java] }
        return model.repository
    }
    private fun waitText(text: String) { compose.waitUntil(15000) { compose.onAllNodesWithText(text,substring=false).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun actions(title: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Actions for $title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Actions for $title").performClick()
        waitText("Delete")
    }
    @Test fun homeRenameCancelValidationPersistenceAndSingleMultiPageSharing() {
        val repo=repo(); val title="Home ${UUID.randomUUID().toString().take(8)}"; val renamed="$title renamed"
        val image=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180,100,40)) }
        val bytes=try { ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { image.recycle() }
        val id=runBlocking { repo.create(title).also { repo.importImage(it,ByteArrayInputStream(bytes),detectDocument=false) } }
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            waitText(title); compose.onNode(hasSetTextAction()).performTextInput(title)
            val before=runBlocking { repo.dao.document(id)!! }; val pages=runBlocking { repo.dao.pages(id) }
            actions(title); compose.onNodeWithText("Rename",substring=false).performClick(); waitText("Rename document")
            compose.onNode(hasSetTextAction() and hasText("Document name")).performTextClearance()
            compose.onNodeWithText("Save",substring=false).assertIsNotEnabled()
            compose.onNodeWithText("Cancel",substring=false).performClick(); assertEquals(before,runBlocking { repo.dao.document(id) })
            actions(title); compose.onNodeWithText("Rename",substring=false).performClick(); waitText("Rename document")
            compose.onNode(hasSetTextAction() and hasText("Document name")).performTextClearance()
            compose.onNode(hasSetTextAction() and hasText("Document name")).performTextInput("  $renamed  ")
            compose.onNodeWithText("Save",substring=false).performClick(); waitText(renamed)
            assertEquals(before.copy(title=renamed),runBlocking { repo.dao.document(id) }); assertEquals(pages,runBlocking { repo.dao.pages(id) })
            compose.activityRule.scenario.recreate(); waitText(renamed)
            fun share(pdf: Boolean,count: Int) {
                actions(renamed); compose.onNodeWithText("Share",substring=false).performClick(); waitText("Share or export")
                val label=if(pdf) "Share as PDF" else "Share as Images"
                compose.waitUntil(15000) { compose.onAllNodes(hasText(label) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.intentresolver")) }
                if(pdf) runBlocking {
                    val asset=temporaryPdfAssets(id).maxBy { it.createdAt }
                    PdfRenderer(ParcelFileDescriptor.open(File(asset.path),ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(count,it.pageCount) }
                } else {
                    val context=InstrumentationRegistry.getInstrumentation().targetContext
                    val folder=File(context.cacheDir,"shared-images").listFiles()!!.filter { File(it,"document").takeIf { marker->marker.exists() }?.readText()==id }.maxBy { it.lastModified() }
                    val files=folder.listFiles()!!.filter { it.extension=="jpg" }.sortedBy { it.name }
                    assertEquals(count,files.size)
                    val intent=imageShareIntent(context,files,List(count) { "$it.jpg" })
                    assertEquals(if(count==1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE,intent.action)
                    val current=runBlocking { repo.dao.pages(id) }
                    files.zip(current).forEach { (file,page)-> assertArrayEquals(File(page.processedImageUri).readBytes(),file.readBytes()) }
                }
                device.pressBack(); waitText(renamed)
            }
            share(true,1); share(false,1)
            runBlocking { val duplicate=repo.duplicatePage(pages.first().id); repo.edit(duplicate,enhancement=Enhancement("Grayscale"),rotation=90) }
            share(true,2); share(false,2)
        } finally { device.pressBack(); runBlocking { repo.purgeForTest(id) } }
    }
    @Test fun recycleRestoreMultiSelectionPermanentConfirmationAndEmptyKeepActive() {
        val repo=repo(); val prefix="Bin ${UUID.randomUUID().toString().take(6)}"
        assertTrue("Empty-bin verification must never delete pre-existing trash",runBlocking { repo.trash.first().isEmpty() })
        val ids=runBlocking { (1..4).map { repo.create("$prefix $it") }.toMutableList() }
        try {
            waitText("$prefix 4"); compose.onNode(hasSetTextAction()).performTextInput("$prefix 1")
            actions("$prefix 1"); compose.onNodeWithText("Delete",substring=false).performClick()
            compose.onNodeWithText("Cancel",substring=false).performClick(); assertNull(runBlocking { repo.dao.document(ids[0])!!.trashedAt })
            actions("$prefix 1"); compose.onNodeWithText("Delete",substring=false).performClick()
            compose.onNodeWithText("Delete",substring=false).performClick()
            compose.waitUntil(15000) { runBlocking { repo.dao.document(ids[0])!!.trashedAt!=null } }
            // The compact library header is intentionally hidden while search has keyboard focus.
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Recycle Bin").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Recycle Bin").performClick(); waitText("$prefix 1")
            compose.activityRule.scenario.recreate(); waitText("$prefix 1")
            compose.onNodeWithContentDescription("Restore $prefix 1").performClick()
            compose.waitUntil(15000) { runBlocking { repo.trash.first().isEmpty() } }
            runBlocking { repo.delete(ids[0]); repo.delete(ids[1]) }; waitText("$prefix 2")
            compose.onNodeWithContentDescription("Select all recycled documents").performClick()
            compose.onNodeWithText("Restore selected").performClick()
            compose.waitUntil(15000) { runBlocking { repo.trash.first().isEmpty() } }
            runBlocking { repo.delete(ids[2]) }; waitText("$prefix 3")
            compose.onNodeWithContentDescription("Permanently delete $prefix 3").performClick(); compose.onNodeWithText("Cancel",substring=false).performClick()
            assertNotNull(runBlocking { repo.dao.document(ids[2]) })
            compose.onNodeWithContentDescription("Permanently delete $prefix 3").performClick()
            compose.onNode(hasText("Delete permanently") and hasClickAction() and !hasContentDescription("Permanently delete $prefix 3")).performClick()
            compose.waitUntil(15000) { runBlocking { repo.dao.document(ids[2])==null } }
            runBlocking { repo.delete(ids[0]); repo.delete(ids[1]) }; waitText("$prefix 2")
            compose.onNodeWithContentDescription("Select all recycled documents").performClick()
            compose.onNodeWithText("Delete selected").performClick(); compose.onNodeWithText("Cancel",substring=false).performClick()
            assertEquals(2,runBlocking { repo.trash.first().size })
            compose.onNodeWithText("Delete selected").performClick()
            compose.onNode(hasText("Delete permanently") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15000) { runBlocking { ids.take(2).all { repo.dao.document(it)==null } } }
            runBlocking { (5..6).forEach { val id=repo.create("$prefix $it"); ids.add(id); repo.delete(id) } }
            waitText("$prefix 6")
            compose.onNodeWithContentDescription("Empty Recycle Bin").performClick(); compose.onNodeWithText("Cancel",substring=false).performClick()
            assertEquals(2,runBlocking { repo.trash.first().size })
            assertEquals(ids.takeLast(2).toSet(),runBlocking { repo.trash.first().map { it.id }.toSet() })
            compose.onNodeWithContentDescription("Empty Recycle Bin").performClick()
            compose.onNodeWithText("Empty Recycle Bin",substring=false).performClick()
            compose.waitUntil(15000) { runBlocking { ids.takeLast(2).all { repo.dao.document(it)==null } } }
            assertNull(runBlocking { repo.dao.document(ids[0]) }); assertNull(runBlocking { repo.dao.document(ids[1]) })
            assertNotNull(runBlocking { repo.dao.document(ids[3]) })
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNode(hasSetTextAction()).performTextClearance(); compose.onNode(hasSetTextAction()).performTextInput("$prefix 4"); waitText("$prefix 4")
        } finally { runBlocking { ids.forEach { repo.purgeForTest(it) } } }
    }
}

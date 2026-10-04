package dev.folio.scanner

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.provider.OpenableColumns
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.ui.imageShareIntent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.*

class DocumentShareTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun documentShareGeneratesPdfSharesMultipleImagesAndExportsBothFormats() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val pdfs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs(); val repo=pdfs.documents
        val bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
        val doc=runBlocking { val id=repo.create("Share flow acceptance"); val page=repo.importImage(id,ByteArrayInputStream(bytes),detectDocument=false); repo.duplicatePage(page); id }
        val device=UiDevice.getInstance(instrumentation)
        fun share() { compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Share document").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }; compose.onNodeWithContentDescription("Share document").performClick() }
        try {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Share flow acceptance").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Share flow acceptance").performClick(); share()
            compose.onAllNodesWithText("Share OCR Text").assertCountEquals(0)
            compose.onNodeWithText("Share as PDF").performScrollTo().performClick()
            compose.waitUntil(60000) { device.hasObject(By.pkg("com.android.intentresolver")) }; device.pressBack()
            runBlocking { val asset=temporaryPdfAssets(doc).single(); PdfRenderer(android.os.ParcelFileDescriptor.open(File(asset.path),android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(2,it.pageCount) } }
            share(); compose.onNodeWithText("Share as Images").performScrollTo().performClick()
            compose.waitUntil(15000) { device.hasObject(By.pkg("com.android.intentresolver")) }
            assertTrue(device.hasObject(By.pkg("com.android.intentresolver")))
            val folder=File(context.cacheDir,"shared-images").listFiles()!!.filter { File(it,"document").takeIf { marker -> marker.exists() }?.readText()==doc }.maxBy { it.lastModified() }
            val files=folder.listFiles()!!.filter { it.extension=="jpg" }.sortedBy { it.name }
            assertEquals(2,files.size)
            val intent=imageShareIntent(context,files,listOf("Page one.jpg","Page two.jpg"))
            assertEquals(Intent.ACTION_SEND_MULTIPLE,intent.action)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0); assertEquals(2,intent.clipData!!.itemCount)
            repeat(2) { i -> val uri=intent.clipData!!.getItemAt(i).uri; assertEquals("content",uri.scheme)
                context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(files[i].readBytes(),it.readBytes()) }
                context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)!!.use { assertTrue(it.moveToFirst()); assertTrue(it.getString(0).endsWith(".jpg")) }
            }
            device.pressBack(); share(); compose.onNodeWithText("Export PDF").performScrollTo().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000))
            val name="share-export-${System.nanoTime()}.pdf"
            device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)!!.text=name
            (device.findObject(By.res("com.google.android.documentsui","action_menu_save")) ?: device.findObject(By.text("SAVE")) ?: device.findObject(By.text("Save")))!!.click()
            compose.waitUntil(60000) { pdfs.work.getWorkInfosByTag("pdf-$doc").get().count { it.state==androidx.work.WorkInfo.State.SUCCEEDED } >=3 }
            val exported=context.contentResolver.persistedUriPermissions.first { !android.provider.DocumentsContract.isTreeUri(it.uri) && context.contentResolver.query(it.uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { row -> row.moveToFirst() && row.getString(0)==name }==true }.uri
            PdfRenderer(context.contentResolver.openFileDescriptor(exported,"r")!!).use { assertEquals(2,it.pageCount) }
            share(); compose.onNodeWithText("Export Images").performScrollTo().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000))
            device.wait(Until.findObject(By.desc("New folder")),5000)!!.click()
            val folderName="Folio-test-${System.nanoTime()}"
            device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000)!!.text=folderName
            (device.findObject(By.text("OK")) ?: device.findObject(By.text("Ok")))!!.click()
            (device.wait(Until.findObject(By.text("USE THIS FOLDER")),10000) ?: device.findObject(By.text("Use this folder")))!!.click()
            (device.wait(Until.findObject(By.text("ALLOW")),5000) ?: device.findObject(By.text("Allow")))!!.click()
            compose.waitUntil(30000) { compose.onAllNodesWithText("Share or export").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty() }
            val tree=context.contentResolver.persistedUriPermissions.first { permission ->
                if(!android.provider.DocumentsContract.isTreeUri(permission.uri)) false else {
                    val root=android.provider.DocumentsContract.buildDocumentUriUsingTree(permission.uri,android.provider.DocumentsContract.getTreeDocumentId(permission.uri))
                    context.contentResolver.query(root,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { row -> row.moveToFirst() && row.getString(0)==folderName }==true
                }
            }.uri
            val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,android.provider.DocumentsContract.getTreeDocumentId(tree))
            context.contentResolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)!!.use { rows ->
                assertEquals(2,rows.count)
                while(rows.moveToNext()) { val uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,rows.getString(0)); context.contentResolver.openInputStream(uri)!!.use { assertNotNull(BitmapFactory.decodeStream(it)?.also { b -> b.recycle() }) } }
            }
        } finally { device.pressBack(); runBlocking { pdfs.work.cancelAllWorkByTag("pdf-$doc").result.get(); repo.purgeForTest(doc) } }
    }
}

package dev.folio.scanner

import android.content.ContentValues
import android.graphics.*
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PdfImagePickerUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun multipleExternalImagesWithExifOrientationProduceReopenablePdf() {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val pdfs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs()
        val device=UiDevice.getInstance(instrumentation)
        val token=java.util.UUID.randomUUID().toString().take(8)
        val title="Image workspace $token"
        val names=listOf("folio-$token-1.jpg","folio-$token-2.jpg")
        val uris=mutableListOf<android.net.Uri>()
        val before=runBlocking { pdfs.documents.dao.allDocuments() }
        var exported:android.net.Uri?=null
        var folder:android.net.Uri?=null
        val folderName="FolioPdfTest$token"
        val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val preferences=context.getSharedPreferences("pdf-utility-destinations",0); val previous=preferences.getString("pdf",null)
        preferences.edit().remove("pdf").commit()
        instrumentation.uiAutomation.executeShellCommand("mkdir -p /sdcard/Documents/$folderName").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        try {
            names.forEachIndexed { index,name ->
                val file=File(context.cacheDir,name)
                val bitmap=Bitmap.createBitmap(400,300,Bitmap.Config.ARGB_8888).apply { eraseColor(if(index==0) Color.RED else Color.BLUE) }
                try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { bitmap.recycle() }
                if(index==0) ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_ROTATE_90.toString()); saveAttributes() }
                val uri=context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg"); put(MediaStore.MediaColumns.RELATIVE_PATH,"Pictures/Folio PDF test/"); put(MediaStore.Images.Media.DATE_TAKEN,System.currentTimeMillis()+365L*86400000L+index*1000)
                })!!
                uris+=uri; context.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }; file.delete()
            }
            compose.onNodeWithText("PDF workspace").performClick()
            compose.onNodeWithText("Image to PDF").performClick()
            val photoSelector=By.descStartsWith("Photo taken on")
            compose.waitUntil(20000) {device.findObjects(photoSelector).size>=2}
            device.waitForIdle()
            device.dumpWindowHierarchy(File(context.cacheDir,"native-gallery.xml"))
            val thumbnails=device.findObjects(photoSelector)
            assertTrue("Native photo picker exposes the two recent fixture thumbnails",thumbnails.size>=2)
            thumbnails[0].click(); device.waitForIdle(); device.findObjects(photoSelector)[1].click()
            device.waitForIdle(); device.dumpWindowHierarchy(File(context.cacheDir,"native-gallery-selected.xml")); val add=device.wait(Until.findObject(By.textStartsWith("Add")),2000) ?: device.findObject(By.textStartsWith("Select")) ?: device.findObject(By.text("Done")) ?: device.findObject(By.text("ADD"))
            assertNotNull("Confirm photo selection",add); add!!.click()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Output filename").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.onNodeWithText("Output filename").performTextReplacement(title)
            compose.onNodeWithText("Choose destination and export").performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),15000))
            device.waitForIdle()
            val chosenFolder=device.wait(Until.findObject(By.text(folderName)),10000)
            assertNotNull("Documents initial location contains the owned test folder",chosenFolder); chosenFolder!!.click()
            val useFolder=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Use this folder"))),10000) ?: device.findObject(By.res("com.google.android.documentsui","action_menu_select"))
            assertNotNull(useFolder); useFolder!!.click()
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)Allow"))),10000)?.click()
            compose.waitUntil(90000) { compose.onAllNodesWithText("Split PDF").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            folder=utility.rememberedDestination("pdf"); assertNotNull("Native tree grant persisted",folder)
            assertEquals(folder,dev.folio.scanner.pdf.PdfUtility(context,utility.engine,utility.documents,utility.pipeline).rememberedDestination("pdf"))
            val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(folder!!,android.provider.DocumentsContract.getTreeDocumentId(folder!!))
            val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(folder!!,android.provider.DocumentsContract.getDocumentId(parent))
            exported=context.contentResolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)!!.use { c -> var uri:android.net.Uri?=null; while(c.moveToNext()) if(c.getString(1)=="$title.pdf") uri=android.provider.DocumentsContract.buildDocumentUriUsingTree(folder!!,c.getString(0)); uri!! }
            android.graphics.pdf.PdfRenderer(context.contentResolver.openFileDescriptor(exported!!,"r")!!).use { assertEquals(2,it.pageCount) }
            val local=File(context.cacheDir,"image-export-proof.pdf"); context.contentResolver.openInputStream(exported!!)!!.use { input -> local.outputStream().use { input.copyTo(it) } }
            try { pdfs.engine.read(local).use { output -> assertEquals(2,output.numberOfPages); assertTrue((1..2).any { output.getPage(it).pageSize.height>output.getPage(it).pageSize.width }); assertTrue((1..2).any { output.getPage(it).pageSize.width>output.getPage(it).pageSize.height }) } } finally { local.delete() }
            assertEquals(before,runBlocking { pdfs.documents.dao.allDocuments() })
        } finally {
            device.pressBack()
            exported?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver,it) }
            folder?.let { android.provider.DocumentsContract.deleteDocument(context.contentResolver,android.provider.DocumentsContract.buildDocumentUriUsingTree(it,android.provider.DocumentsContract.getTreeDocumentId(it))) }
            preferences.edit().apply { if(previous==null) remove("pdf") else putString("pdf",previous) }.commit()
            uris.forEach { context.contentResolver.delete(it,null,null) }
        }
    }
}

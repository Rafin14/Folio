package dev.folio.scanner

import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.ui.*
import org.junit.Assert.*
import org.junit.Test

class PdfPickerIntentTest {
    @Test fun pdfAndFolderIntentsUseNativeDocumentsHintsAndImagePickerUsesGallery() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val single=PdfInputPicker().createIntent(context,arrayOf("application/pdf"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,single.action); assertEquals("application/pdf",single.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.single()); assertFalse(single.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,true))
        assertEquals(documentsLocation,androidx.core.content.IntentCompat.getParcelableExtra(single,DocumentsContract.EXTRA_INITIAL_URI,android.net.Uri::class.java))
        assertTrue(PdfInputPicker(true).createIntent(context,arrayOf("application/pdf")).getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false))
        val folder=PdfDestinationPicker().createIntent(context,null)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,folder.action); assertEquals(documentsLocation,androidx.core.content.IntentCompat.getParcelableExtra(folder,DocumentsContract.EXTRA_INITIAL_URI,android.net.Uri::class.java))
        val gallery=ActivityResultContracts.PickMultipleVisualMedia(50).createIntent(context,androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        assertEquals("image/*",gallery.type); assertNotEquals(Intent.ACTION_CREATE_DOCUMENT,gallery.action)
    }
}

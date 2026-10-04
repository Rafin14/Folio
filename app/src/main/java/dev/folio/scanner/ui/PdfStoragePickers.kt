package dev.folio.scanner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

internal val documentsLocation:Uri get()=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents")

/** The platform may ignore this hint; MIME filtering and Android's access rules remain native. */
internal class PdfInputPicker(private val multiple:Boolean=false):ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context:Context,input:Array<String>):Intent=super.createIntent(context,input).putExtra(DocumentsContract.EXTRA_INITIAL_URI,documentsLocation).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multiple)
}
internal class PdfDestinationPicker:ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context:Context,input:Uri?):Intent=super.createIntent(context,input ?: documentsLocation)
}

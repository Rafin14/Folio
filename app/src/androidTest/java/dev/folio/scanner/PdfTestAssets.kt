package dev.folio.scanner

import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.data.PdfAsset
import java.io.File

/** Test observation of short-lived share/print results, not a persistent PDF database. */
suspend fun temporaryPdfAssets(document:String):List<PdfAsset> {
    val context=ApplicationProvider.getApplicationContext<android.content.Context>()
    val pdfs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs()
    return File(context.cacheDir,"shared-pdfs").listFiles().orEmpty().flatMap { pdfs.results(it.name) }.filter { it.documentId==document }.sortedBy { it.createdAt }
}

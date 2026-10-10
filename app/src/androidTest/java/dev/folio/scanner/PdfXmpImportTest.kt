package dev.folio.scanner

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfXmpImportTest {
    @Test fun xmpMetadataSurvivesImportReopenAndPageRendering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val utility = EntryPointAccessors.fromApplication(context, PdfWorkerDependencies::class.java).utility()
        val source = File(context.cacheDir, "shared-pdfs/xmp-regression.pdf").apply { parentFile!!.mkdirs() }
        PdfDocument(PdfWriter(source.path, WriterProperties().addXmpMetadata())).use {
            it.documentInfo.setTitle("Folio XMP regression")
            repeat(2) { _ -> it.addNewPage() }
        }
        // Retain this non-sensitive fixture for the optimized-APK native-picker regression check.
        source.copyTo(File(context.getExternalFilesDir(null), "xmp-regression.pdf"), overwrite = true)
        val original = source.readBytes()
        val sessions = mutableListOf<String>()
        var document = ""
        try {
            val uri = FileProvider.getUriForFile(context, "dev.folio.scanner.files", source)
            val initial = utility.open("edit", listOf(uri), initialImport = true).also(sessions::add)
            document = utility.persistInitialImport(initial)
            val managed = utility.openManaged(document).also(sessions::add)
            utility.engine.read(File(utility.folder(managed), "input-0")).use {
                assertEquals(2, it.numberOfPages)
                assertNotNull(it.xmpMetadata)
            }
            utility.preview(managed, utility.pages(managed).first()).let {
                try { assertTrue(it.width > 0 && it.height > 0) } finally { it.recycle() }
            }
            assertArrayEquals(original, source.readBytes())
        } finally {
            sessions.forEach { utility.discard(it) }
            if (document.isNotEmpty()) utility.documents.purgeForTest(document)
            source.delete()
        }
    }
}

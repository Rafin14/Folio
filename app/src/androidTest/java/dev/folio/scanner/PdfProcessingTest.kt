package dev.folio.scanner

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfStream
import dev.folio.scanner.pdf.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfProcessingTest {
    @Test fun incrementalLargeImagesMixedOrientationAndFiftyOnePagesOpenInNativeRenderer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "pdf-native-${System.nanoTime()}").apply { mkdirs() }
        val engine = PdfEngine()
        try {
            val image = File(dir, "large.jpg")
            val bitmap = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLUE; textSize = 85f }
                for (y in 100..3900 step 100) canvas.drawText("Folio real image input $y", 100f, y.toFloat(), paint)
                image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            } finally { bitmap.recycle() }
            val original = File(dir, "original.pdf"); engine.generate(listOf(image), original, "Large source")
            engine.read(original).use { pdf ->
                val stream = pdf.getPage(1).resources.pdfObject.getAsDictionary(PdfName.XObject).values().first() as PdfStream
                assertEquals(3000, stream.getAsNumber(PdfName.Width).intValue())
                assertArrayEquals(image.readBytes(), stream.getBytes(false))
            }
            val balanced = File(dir, "balanced.pdf"); engine.generate(listOf(image), balanced, "Balanced", PdfQuality.BALANCED)
            val small = File(dir, "small.pdf"); engine.generate(listOf(image), small, "Small", PdfQuality.SMALL)
            engine.read(balanced).use { pdf -> val stream = pdf.getPage(1).resources.pdfObject.getAsDictionary(PdfName.XObject).values().first() as PdfStream; assertEquals(2400, stream.getAsNumber(PdfName.Height).intValue()) }
            assertTrue(small.length() < original.length())
            val compressedPdf = File(dir, "compressed-copy.pdf")
            engine.combine(listOf(PdfSource(original, listOf(1))), compressedPdf, "Compressed PDF", quality = PdfQuality.SMALL)
            engine.read(compressedPdf).use { pdf ->
                val stream = pdf.getPage(1).resources.pdfObject.getAsDictionary(PdfName.XObject).values().first() as PdfStream
                assertEquals(1600, stream.getAsNumber(PdfName.Height).intValue())
                assertEquals(1920f, pdf.getPage(1).pageSize.height, .1f)
            }
            assertTrue(compressedPdf.length() < original.length())
            engine.render(compressedPdf, 1, dir).let { assertTrue(it.height > it.width); it.recycle() }
            val repeated = File(dir, "fifty.pdf"); var finished = 0
            engine.generate(List(51) { image }, repeated, "Fifty-one pages", PdfQuality.ORIGINAL, progress = { done, total -> assertEquals(51, total); finished = done })
            assertEquals(51, finished)
            PdfRenderer(ParcelFileDescriptor.open(repeated, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(51, renderer.pageCount)
                renderer.openPage(50).use { page -> val preview = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888); try { page.render(preview, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertTrue(preview.getPixel(150, 300) != Color.TRANSPARENT) } finally { preview.recycle() } }
            }
            // Exercise the same incremental copy used by native printing with 51 full-resolution pages.
            val printedLarge=File(dir,"fifty-one-print.pdf")
            val printStarted=android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.runBlocking {
                ParcelFileDescriptor.open(printedLarge,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
                    val written=writePrintedPages(repeated,"Large print",printAttributes(repeated),arrayOf(android.print.PageRange.ALL_PAGES),descriptor,android.os.CancellationSignal())
                    assertEquals(51,written.sumOf { it.end-it.start+1 })
                }
            }
            engine.read(printedLarge).use { pdf ->
                assertEquals(51,pdf.numberOfPages)
                val form=pdf.getPage(51).resources.pdfObject.getAsDictionary(PdfName.XObject).values().first() as PdfStream
                val embedded=form.getAsDictionary(PdfName.Resources).getAsDictionary(PdfName.XObject).values().first() as PdfStream
                assertEquals(3000,embedded.getAsNumber(PdfName.Width).intValue()); assertEquals(4000,embedded.getAsNumber(PdfName.Height).intValue())
                assertArrayEquals(image.readBytes(),embedded.getBytes(false))
            }
            PdfRenderer(ParcelFileDescriptor.open(printedLarge,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> assertEquals(51,renderer.pageCount); renderer.openPage(50).use { assertTrue(it.height>it.width) } }
            android.util.Log.i("Folio print test","51 x 3000x4000 copied in ${android.os.SystemClock.elapsedRealtime()-printStarted} ms; output ${printedLarge.length()} bytes")
            val copiedLarge = File(dir, "fifty-one-copy.pdf")
            engine.combine(listOf(PdfSource(repeated, (1..51).reversed().toList())), copiedLarge, "Large reversed copy")
            assertEquals(51, engine.count(copiedLarge))
            engine.render(copiedLarge, 51, dir).let { assertEquals(1400, maxOf(it.width, it.height)); it.recycle() }
            val landscape = File(dir, "landscape.jpg")
            Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).let { bitmap -> try { bitmap.eraseColor(Color.WHITE); landscape.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } } finally { bitmap.recycle() } }
            listOf(5, 10).forEach { count ->
                val mixed = File(dir, "mixed-$count.pdf")
                engine.generate(List(count) { if (it % 2 == 0) image else landscape }, mixed, "Mixed orientation", PdfQuality.SMALL)
                PdfRenderer(ParcelFileDescriptor.open(mixed, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertEquals(count, renderer.pageCount)
                    renderer.openPage(0).use { assertTrue(it.height > it.width) }
                    renderer.openPage(1).use { assertTrue(it.width > it.height) }
                }
            }
            engine.render(original, 1, dir).let { assertEquals(1400, maxOf(it.width, it.height)); it.recycle() }
            val encrypted = File(dir, "protected.pdf"); engine.generate(listOf(image), encrypted, "Protected", PdfQuality.SMALL, "safe-father")
            engine.render(encrypted, 1, dir, "safe-father").let { assertTrue(it.width > 0); it.recycle() }
        } finally { dir.deleteRecursively() }
    }
}

package dev.folio.scanner

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import dev.folio.scanner.pdfanalysis.PdfiumDocument
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfiumTest {
    @Test fun nativeTextGeometryRotationAndRenderingUsePdfium() {
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"pdfium-test.pdf")
        try {
            PdfDocument(PdfWriter(file)).use { pdf ->
                val page=pdf.addNewPage();val canvas=PdfCanvas(page)
                canvas.beginText().setFontAndSize(PdfFontFactory.createFont(),14f).moveText(50.0,700.0).showText("Native PDFium words").endText()
                canvas.rectangle(50.0,500.0,200.0,100.0).stroke()
                pdf.addNewPage().setRotation(90)
            }
            PdfiumDocument(file).use { pdf ->
                assertEquals(2,pdf.count);assertTrue(pdf.text(0).contains("Native PDFium words"))
                val bitmap=pdf.render(0,900)
                try {assertEquals(900,bitmap.height);val objects=pdf.inspect(0,bitmap.width,bitmap.height)
                    assertTrue(objects.getJSONArray("objects").length()>=2);assertTrue(objects.getJSONArray("characters").length()>10)
                    assertTrue(bitmap.config==Bitmap.Config.ARGB_8888)
                } finally {bitmap.recycle()}
                assertTrue(pdf.size(1)[0]>pdf.size(1)[1])
                pdf.render(1,600).recycle()
            }
        } finally {file.delete()}
    }
}

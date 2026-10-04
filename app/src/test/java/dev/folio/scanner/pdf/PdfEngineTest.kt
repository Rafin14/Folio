package dev.folio.scanner.pdf

import com.itextpdf.kernel.pdf.*
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.*
import org.junit.Test

class PdfEngineTest {
    @Test fun generationCopyOrderRotationExtractionSplitMergeAndEncryption() {
        val dir = Files.createTempDirectory("folio-pdf-test").toFile()
        val engine = PdfEngine()
        try {
            val images = (1..3).map { index -> File(dir, "$index.jpg").apply { ImageIO.write(BufferedImage(index * 150, 300, BufferedImage.TYPE_INT_RGB), "jpg", this) } }
            val file = File(dir, "source.pdf"); val progress = mutableListOf<Int>()
            engine.generate(images, file, "Unit pages", progress = { done, _ -> progress += done })
            assertEquals(listOf(0, 1, 2, 3), progress)
            engine.read(file).use { pdf ->
                assertEquals(3, pdf.numberOfPages)
                assertEquals(216f, pdf.getPage(3).pageSize.width, .1f)
                val image = pdf.getPage(3).resources.pdfObject.getAsDictionary(PdfName.XObject).values().first() as PdfStream
                assertEquals(450, image.getAsNumber(PdfName.Width).intValue())
                assertTrue(pdf.documentInfo.producer.contains("iText"))
                assertTrue(pdf.documentInfo.getMoreInfo("License").contains("AGPL"))
            }
            val edited = File(dir, "edited.pdf")
            engine.combine(listOf(PdfSource(file, pageSelection("3,1", 3), listOf(90, 0))), edited, "Edited")
            engine.read(edited).use { pdf -> assertEquals(2, pdf.numberOfPages); assertEquals(90, pdf.getPage(1).rotation); assertEquals(216f, pdf.getPage(1).pageSize.width, .1f); assertEquals(72f, pdf.getPage(2).pageSize.width, .1f) }
            val parts = (1..3).map { index -> File(dir, "part-$index.pdf").also { engine.combine(listOf(PdfSource(file, listOf(index))), it, "Part $index") } }
            val merged = File(dir, "merged.pdf")
            engine.combine(parts.reversed().map { PdfSource(it, listOf(1)) }, merged, "Merged", "father-safe")
            assertThrows(Exception::class.java) { engine.count(merged) }
            assertThrows(Exception::class.java) { engine.count(merged, "wrong") }
            engine.read(merged, "father-safe").use { pdf -> assertTrue(pdf.reader.isEncrypted); assertEquals(3, pdf.numberOfPages); assertEquals(216f, pdf.getPage(1).pageSize.width, .1f) }
            val unlocked = File(dir, "unlocked.pdf")
            engine.combine(listOf(PdfSource(merged, listOf(2), password = "father-safe")), unlocked, "Unlocked")
            assertEquals(1, engine.count(unlocked))
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsInvalidSelectionAndPreservesExplicitOrder() {
        assertEquals(listOf(3, 1, 2), pageSelection("3,1-2", 3))
        assertEquals(listOf(1, 2, 3), pageSelection("", 3))
        listOf("0", "4", "2-1", "1,1", "1-999999999", "a", "1,").forEach { input -> assertThrows(Exception::class.java) { pageSelection(input, 3) } }
    }
    @Test fun copyingPagesRetainsVectorTextWithoutRasterization() {
        val dir = Files.createTempDirectory("folio-pdf-text").toFile()
        try {
            val source = File(dir, "text.pdf")
            PdfDocument(PdfWriter(source.path)).use { pdf ->
                val font = com.itextpdf.kernel.font.PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
                listOf("Receipt A", "Receipt B").forEach { text ->
                    com.itextpdf.kernel.pdf.canvas.PdfCanvas(pdf.addNewPage()).beginText().setFontAndSize(font, 16f).moveText(50.0, 700.0).showText(text).endText()
                }
            }
            val output = File(dir, "reordered.pdf"); val engine = PdfEngine()
            engine.combine(listOf(PdfSource(source, listOf(2, 1))), output, "Reordered text")
            engine.read(output).use { pdf ->
                assertEquals("Receipt B", com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1)))
                assertEquals("Receipt A", com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(2)))
            }
        } finally { dir.deleteRecursively() }
    }
}

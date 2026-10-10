package dev.folio.scanner.pdfanalysis

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OcrModeTest {
    @Test fun currentPageIsScopedAndWholeDocumentKeepsOrder() {
        assertEquals(listOf(3),ocrTargets(3,8))
        assertEquals((0 until 500).toList(),ocrTargets(null,500))
    }
    @Test fun invalidPageAndDocumentSizesAreRejected() {
        listOf(-1,8).forEach {page ->assertThrows(IllegalArgumentException::class.java) {ocrTargets(page,8)}}
        listOf(0,501).forEach {count ->assertThrows(IllegalArgumentException::class.java) {ocrTargets(null,count)}}
    }
    @Test fun fullPageResultSupersedesOnlyItsOwnPageWithoutRemovingLayout() {
        val folder=Files.createTempDirectory("folio-ocr-mode").toFile()
        try {
            val layout=java.io.File(folder,"analysis-1.json").apply {writeText("layout")}
            assertEquals(layout,ocrResultFile(folder,1))
            val full=java.io.File(folder,"full-ocr-1.json").apply {writeText("full page")}
            assertEquals(full,ocrResultFile(folder,1))
            assertEquals("analysis-0.json",ocrResultFile(folder,0).name)
            assertEquals("layout",layout.readText())
            full.delete();assertEquals(layout,ocrResultFile(folder,1))
        } finally {folder.deleteRecursively()}
    }
}

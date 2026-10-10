package dev.folio.scanner.pdfanalysis

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class WordConversionTest {
    @Test fun gridRecognitionReconstructsHorizontalAndVerticalMergedCells() {
        val h=listOf(GridLine(0f,0f,200f),GridLine(50f,0f,200f),GridLine(100f,0f,200f))
        val v=listOf(GridLine(0f,0f,100f),GridLine(100f,50f,100f),GridLine(200f,0f,100f))
        val table=reconstructGrid(h,v,0) {"Editable cell"}!!
        assertEquals(2,table.rows);assertEquals(2,table.widths.size);assertEquals(3,table.cells.size)
        assertEquals(2,table.cells.first().columnSpan)
        val merged=reconstructGrid(listOf(h.first(),GridLine(50f,100f,200f),h.last()),listOf(v.first(),GridLine(100f,0f,100f),v.last()),0) {"Merged row"}!!
        assertEquals(2,merged.cells.first().rowSpan)
        assertNull(reconstructGrid(h,listOf(v.first()),0) {"Not a table"})
    }
    @Test fun docxContainsEditableTextTablesHeadingsAndSeparateLayoutProperties() {
        val folder=Files.createTempDirectory("word-test").toFile()
        try {
            val table=reconstructGrid(listOf(GridLine(200f,20f,220f),GridLine(250f,20f,220f),GridLine(300f,20f,220f)),listOf(GridLine(20f,200f,300f),GridLine(120f,250f,300f),GridLine(220f,200f,300f)),1) {"Cell & value"}!!
            val p=WordPage(595.0,842.0,595,842,listOf(WordParagraph(Box(20f,20f,300f,70f),0,listOf(WordRun("Editable <heading> & text",18.0,true)),1),table))
            WordMode.entries.forEach {mode ->
                val output=java.io.File(folder,"${mode.name}.docx")
                val result=writeDocx(output,folder,2,mode,{p})
                assertEquals(2,result.paragraphs);assertEquals(2,result.tables)
                ZipFile(output).use {zip ->
                    val xml=zip.getInputStream(zip.getEntry("word/document.xml")).use {it.readBytes()}
                    val factory=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}
                    val document=factory.newDocumentBuilder().parse(xml.inputStream())
                    val ns="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                    assertEquals(2,document.getElementsByTagNameNS(ns,"tbl").length)
                    assertEquals(2,document.getElementsByTagNameNS(ns,"gridSpan").length)
                    assertEquals(2,document.getElementsByTagNameNS(ns,"sectPr").length)
                    assertEquals(mode==WordMode.LAYOUT,document.getElementsByTagNameNS(ns,"framePr").length>0)
                    assertTrue(document.documentElement.textContent.contains("Editable <heading> & text"))
                    assertNotNull(zip.getEntry("word/styles.xml"));assertNotNull(zip.getEntry("word/numbering.xml"))
                    assertNull(zip.getEntry("word/media/page.png"))
                    zip.entries().asSequence().filter {it.name.endsWith(".xml") || it.name.endsWith(".rels")}.forEach {entry ->zip.getInputStream(entry).use {factory.newDocumentBuilder().parse(it)}}
                }
            }
        } finally {folder.deleteRecursively()}
    }
    @Test fun cancellationLeavesNoIncompleteDocx() {
        val folder=Files.createTempDirectory("word-cancel").toFile();val output=java.io.File(folder,"cancelled.docx")
        try {
            try {writeDocx(output,folder,2,WordMode.EDITABLE,{if(it==1) throw java.util.concurrent.CancellationException();WordPage(595.0,842.0,595,842,emptyList())});fail("Cancellation ignored")} catch(_:java.util.concurrent.CancellationException) {}
            assertFalse(output.exists());assertFalse(java.io.File(folder,"word-document.xml").exists())
        } finally {folder.deleteRecursively()}
    }
}

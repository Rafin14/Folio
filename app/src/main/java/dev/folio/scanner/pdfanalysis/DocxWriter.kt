package dev.folio.scanner.pdfanalysis

import java.io.File
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.*

private fun xml(value:String)=buildString {value.forEach {c ->when(c) {'&'->append("&amp;");'<'->append("&lt;");'>'->append("&gt;");'"'->append("&quot;");'\''->append("&apos;");else->if(c.code>=32 || c in listOf('\n','\t','\r')) append(c)}}}
private const val W="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
data class DocxResult(val paragraphs:Int,val tables:Int,val figures:Int,val warnings:List<String>)

/** OOXML is streamed to disk; only one structured page and its figure assets are materialized. */
fun writeDocx(output:File,temporary:File,count:Int,mode:WordMode,page:(Int)->WordPage,cancelled:()->Unit={},progress:(Int,Int)->Unit={_,_->}):DocxResult {
    require(count in 1..500)
    val xmlFile=File(temporary,"word-document.xml");val media=mutableListOf<Pair<String,File>>()
    val warnings=mutableListOf<String>();val numbers=linkedMapOf<Int,Int>();var nextNumberId=3;var currentNumberId=2;var expectedNumber=-1
    var paragraphs=0;var tables=0;var figures=0
    fun Writer.run(value:WordRun) {
        append("<w:r><w:rPr><w:rFonts w:ascii=\"").append(xml(value.font)).append("\" w:hAnsi=\"").append(xml(value.font)).append("\"/>")
        if(value.bold) append("<w:b/>");if(value.italic) append("<w:i/>")
        append("<w:sz w:val=\"").append((value.size*2).roundToInt().coerceIn(8,144).toString()).append("\"/></w:rPr>")
        value.text.split('\n').forEachIndexed {i,line ->if(i>0) append("<w:br/>");append("<w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t>")};append("</w:r>")
    }
    fun Writer.section(p:WordPage,next:Boolean) {
        val width=(p.widthPt*20).roundToInt();val height=(p.heightPt*20).roundToInt()
        append("<w:sectPr>");if(next) append("<w:type w:val=\"nextPage\"/>")
        append("<w:pgSz w:w=\"$width\" w:h=\"$height\"");if(width>height) append(" w:orient=\"landscape\"");append("/><w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"360\" w:footer=\"360\" w:gutter=\"0\"/></w:sectPr>")
    }
    fun Writer.frame(box:Box,p:WordPage) {
        if(mode!=WordMode.LAYOUT) return
        val x=(box.left/p.width*p.widthPt*20).roundToInt();val y=(box.top/p.height*p.heightPt*20).roundToInt();val width=(box.width/p.width*p.widthPt*20).roundToInt().coerceAtLeast(100)
        append("<w:framePr w:x=\"$x\" w:y=\"$y\" w:w=\"$width\" w:xAnchor=\"page\" w:yAnchor=\"page\" w:wrap=\"none\"/>")
    }
    try {
        xmlFile.bufferedWriter().use {out ->
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>")
            repeat(count) {index ->
                cancelled();progress(index,count);val p=page(index);warnings+=p.warnings
                p.blocks.forEach {block ->
                    cancelled()
                    when(block) {
                        is WordParagraph -> {
                            paragraphs++;out.append("<w:p><w:pPr>")
                            if(block.heading>0) out.append("<w:pStyle w:val=\"Heading${block.heading}\"/>")
                            out.frame(block.box,p)
                            if(block.list.isNotEmpty()) {
                                val id=if(block.list=="bullet") {expectedNumber=-1;1} else {
                                    val number=block.list.substringAfter(':').toIntOrNull() ?: 1
                                    if(number!=expectedNumber) {currentNumberId=nextNumberId++;numbers[currentNumberId]=number};expectedNumber=number+1;currentNumberId
                                }
                                out.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"$id\"/></w:numPr>")
                            } else expectedNumber=-1
                            out.append("<w:spacing w:after=\"120\"/><w:jc w:val=\"${block.alignment}\"/></w:pPr>");block.runs.forEach {out.run(it)};out.append("</w:p>")
                        }
                        is WordTable -> {
                            tables++;val total=(min(block.box.width/p.width*p.widthPt,p.widthPt-72)*20).roundToInt().coerceAtLeast(200)
                            val widths=block.widths.map {(it/block.widths.sum()*total).roundToInt().coerceAtLeast(20)}
                            out.append("<w:tbl><w:tblPr>")
                            if(mode==WordMode.LAYOUT) out.append("<w:tblpPr w:horzAnchor=\"page\" w:vertAnchor=\"page\" w:tblpX=\"${(block.box.left/p.width*p.widthPt*20).roundToInt()}\" w:tblpY=\"${(block.box.top/p.height*p.heightPt*20).roundToInt()}\"/>")
                            out.append("<w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblBorders>");listOf("top","left","bottom","right","insideH","insideV").forEach {out.append("<w:$it w:val=\"single\" w:sz=\"4\" w:color=\"auto\"/>")};out.append("</w:tblBorders></w:tblPr><w:tblGrid>");widths.forEach {out.append("<w:gridCol w:w=\"$it\"/>")};out.append("</w:tblGrid>")
                            repeat(block.rows) {row ->out.append("<w:tr>");var col=0
                                while(col<widths.size) {val cell=block.cells.first {row in it.row until it.row+it.rowSpan && col in it.column until it.column+it.columnSpan}
                                    val width=widths.subList(cell.column,cell.column+cell.columnSpan).sum();out.append("<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/>")
                                    if(cell.columnSpan>1) out.append("<w:gridSpan w:val=\"${cell.columnSpan}\"/>")
                                    if(cell.rowSpan>1) out.append(if(row==cell.row) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                                    out.append("</w:tcPr><w:p><w:pPr><w:jc w:val=\"${cell.alignment}\"/></w:pPr>")
                                    if(row==cell.row) out.run(WordRun(cell.text,cell.size,cell.bold,font=cell.font));out.append("</w:p></w:tc>");col=cell.column+cell.columnSpan
                                };out.append("</w:tr>")
                            };out.append("</w:tbl><w:p/>")
                        }
                        is WordFigure -> {
                            figures++;val name="image${media.size+1}.${if(block.image.extension.lowercase()=="jpg") "jpg" else "png"}";media+=name to block.image
                            val id=media.size;val width=min(block.box.width/p.width*p.widthPt,p.widthPt-72);val height=width*block.box.height*p.width/(block.box.width*p.height)*p.heightPt/p.widthPt
                            val cx=(width*12700).roundToLong().coerceAtLeast(1);val cy=(height*12700).roundToLong().coerceAtLeast(1)
                            out.append("<w:p><w:pPr>");out.frame(block.box,p);out.append("</w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$id\" name=\"Figure $id\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"$id\" name=\"$name\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"image$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
                        }
                    }
                }
                if(index<count-1) {out.append("<w:p><w:pPr>");out.section(p,true);out.append("</w:pPr></w:p>")} else out.section(p,false)
            };out.append("</w:body></w:document>")
        }
        ZipOutputStream(output.outputStream().buffered()).use {zip ->
            fun entry(name:String,value:String) {cancelled();zip.putNextEntry(ZipEntry(name));zip.write(value.toByteArray());zip.closeEntry()}
            entry("[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/><Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/></Types>")
            entry("_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"document\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>")
            val relations=buildString {append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"styles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/><Relationship Id=\"numbering\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>");media.forEachIndexed {i,(name,_) ->append("<Relationship Id=\"image${i+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/$name\"/>")};append("</Relationships>")}
            entry("word/_rels/document.xml.rels",relations)
            entry("word/styles.xml","<w:styles xmlns:w=\"$W\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"32\"/></w:rPr></w:style><w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style></w:styles>")
            entry("word/numbering.xml",buildString {append("<w:numbering xmlns:w=\"$W\">");listOf(1 to "bullet",2 to "decimal").forEach {(id,format) ->append("<w:abstractNum w:abstractNumId=\"$id\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"$format\"/><w:lvlText w:val=\"${if(id==1) "•" else "%1."}\"/><w:pPr><w:ind w:left=\"360\" w:hanging=\"180\"/></w:pPr></w:lvl></w:abstractNum>")};listOf(1,2).forEach {id ->append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"$id\"/></w:num>")};numbers.forEach {(id,start) ->append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"2\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"$start\"/></w:lvlOverride></w:num>")};append("</w:numbering>")})
            zip.putNextEntry(ZipEntry("word/document.xml"));xmlFile.inputStream().use {input ->val b=ByteArray(65536);while(true) {cancelled();val n=input.read(b);if(n<0) break;zip.write(b,0,n)}};zip.closeEntry()
            media.forEach {(name,file) ->cancelled();zip.putNextEntry(ZipEntry("word/media/$name"));file.inputStream().use {input ->val b=ByteArray(65536);while(true) {cancelled();val n=input.read(b);if(n<0) break;zip.write(b,0,n)}};zip.closeEntry()}
        }
        progress(count,count);return DocxResult(paragraphs,tables,figures,warnings)
    } catch(error:Throwable) {output.delete();throw error}
    finally {xmlFile.delete();media.forEach {if(it.second.canonicalFile.toPath().startsWith(temporary.canonicalFile.toPath())) it.second.delete()}}
}

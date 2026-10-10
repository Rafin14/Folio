package dev.folio.scanner.pdfanalysis

import android.graphics.Bitmap
import java.io.File

fun reconstructWordPage(page:AnalysisPage,bitmap:Bitmap,pdf:PdfiumDocument,folder:File,cancelled:()->Unit={}):WordPage {
    val blocks=mutableListOf<WordBlock>();val warnings=mutableListOf<String>()
    val figures=reconcileFigures(page).filter {figure ->
        // A scanned background is not an editable figure and must not become a screenshot document.
        figure.box.width*figure.box.height<page.width*page.height*.8f || page.text.isBlank()
    }
    val visualRegions=figures.map {it.box}
    for(region in page.regions.sortedBy {it.order}) {
        cancelled()
        if(region.label in setOf("image","chart","header_image","footer_image","display_formula","seal")) continue
        if(region.text.isBlank()) continue
        if(region.label=="table") {
            val table=recognizeTable(bitmap,page,region)
            if(table!=null) {blocks+=table;continue}
            warnings+="Page ${page.index+1}: table borders could not establish a reliable cell grid; table text remains editable paragraphs."
        }
        if(visualRegions.any {it.overlap(region.box)>.8f}) continue
        val chars=List(page.characters.length()) {page.characters.getJSONObject(it)}.filter {val b=boxFrom(it.getJSONArray("box"));region.box.contains((b.left+b.right)/2,(b.top+b.bottom)/2)}
        val font=chars.map {it.optString("font")}.filter {it.isNotBlank()}.groupingBy {it}.eachCount().maxByOrNull {it.value}?.key?.substringAfter('+') ?: "Arial"
        val italic=chars.count {it.optInt("flags") and 64!=0}>chars.size/2
        val heading=when(region.label) {"doc_title"->1;"paragraph_title"->2;else->0}
        val number=Regex("^\\s*(\\d+)[.)]\\s+").find(region.text)
        val bullet=Regex("^\\s*[•●▪]\\s+").find(region.text)
        val list=if(number!=null) "number:${number.groupValues[1]}" else if(bullet!=null) "bullet" else ""
        val text=if(number!=null) region.text.substring(number.range.last+1) else if(bullet!=null) region.text.substring(bullet.range.last+1) else region.text
        val alignment=if(region.box.left>page.width*.15 && region.box.right<page.width*.85 && kotlin.math.abs((region.box.left+region.box.right)/2-page.width/2)<page.width*.05) "center" else "left"
        blocks+=WordParagraph(region.box,region.order,listOf(WordRun(text,region.fontSize,region.bold,italic,font)),heading,alignment,list)
    }
    for(figure in figures) {
        cancelled()
        val output=extractFigure(pdf,page,figure.copy(format=FigureFormat.PNG),folder,cancelled)
        val image=if(output.format==FigureFormat.VECTOR) {
            val png=File(folder,"word-${figure.id}.png")
            PdfiumDocument(output.file).use {vector ->val rendered=vector.render(0,2400);try {png.outputStream().use {check(rendered.compress(Bitmap.CompressFormat.PNG,100,it))}} finally {rendered.recycle()}}
            output.file.delete();png
        } else output.file
        val order=page.regions.filter {it.box.overlap(figure.box)>.6f}.minOfOrNull {it.order} ?: (page.regions.maxOfOrNull {it.order} ?: 0)+1
        blocks+=WordFigure(figure.box,order,image,figure.caption)
    }
    return WordPage(page.widthPt,page.heightPt,page.width,page.height,blocks.sortedWith(compareBy<WordBlock> {it.order}.thenBy {it.box.top}.thenBy {it.box.left}),warnings)
}

package dev.folio.scanner.pdfanalysis

import java.io.File

enum class OcrMode(val label:String) {
    LAYOUT_AWARE("Layout-Aware OCR"), FULL_PAGE("Full Page OCR")
}

fun ocrTargets(page:Int?,count:Int):List<Int> {
    require(count in 1..500)
    return if(page==null) (0 until count).toList() else listOf(page.also {require(it in 0 until count)})
}

/** Full-page OCR replaces the displayed text, while layout checkpoints still serve figures/Word. */
internal fun ocrResultFile(folder:File,index:Int):File =
    File(folder,"full-ocr-$index.json").takeIf {it.isFile} ?: File(folder,"analysis-$index.json")

package dev.folio.scanner.pdfanalysis

import java.io.File

enum class WordMode { EDITABLE,LAYOUT }
data class WordRun(val text:String,val size:Double=11.0,val bold:Boolean=false,val italic:Boolean=false,val font:String="Arial")
sealed interface WordBlock {val box:Box;val order:Int}
data class WordParagraph(override val box:Box,override val order:Int,val runs:List<WordRun>,val heading:Int=0,val alignment:String="left",val list:String=""):WordBlock
data class WordCell(val row:Int,val column:Int,val rowSpan:Int,val columnSpan:Int,val text:String,val alignment:String="left",val size:Double=10.0,val bold:Boolean=false,val font:String="Arial")
data class WordTable(override val box:Box,override val order:Int,val rows:Int,val widths:List<Float>,val cells:List<WordCell>):WordBlock
data class WordFigure(override val box:Box,override val order:Int,val image:File,val caption:String=""):WordBlock
data class WordPage(val widthPt:Double,val heightPt:Double,val width:Int,val height:Int,val blocks:List<WordBlock>,val warnings:List<String> = emptyList())

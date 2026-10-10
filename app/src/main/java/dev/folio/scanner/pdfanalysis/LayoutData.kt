package dev.folio.scanner.pdfanalysis

import kotlin.math.*

data class Box(val left:Float,val top:Float,val right:Float,val bottom:Float) {
 val width get()=right-left; val height get()=bottom-top
 fun clipped(w:Int,h:Int)=Box(left.coerceIn(0f,w.toFloat()),top.coerceIn(0f,h.toFloat()),right.coerceIn(0f,w.toFloat()),bottom.coerceIn(0f,h.toFloat()))
}
data class LayoutDetection(val id:Int,val label:String,val confidence:Float,val box:Box,val order:Int)
data class OcrLine(val text:String,val confidence:Float,val box:Box,val layoutId:Int)
val LABELS=listOf("abstract","algorithm","aside_text","chart","content","display_formula","doc_title","figure_title","footer","footer_image","footnote","formula_number","header","header_image","image","inline_formula","number","paragraph_title","reference","reference_content","seal","table","text","vertical_text","vision_footnote")
val TEXT_LABELS=setOf("abstract","algorithm","aside_text","content","doc_title","figure_title","footer","footnote","formula_number","header","number","paragraph_title","reference","reference_content","table","text","vertical_text","vision_footnote")
fun decodeLayout(values:FloatArray,w:Int,h:Int,threshold:Float=.5f):List<LayoutDetection> {
 require(values.size%7==0 && w>0 && h>0 && threshold in 0f..1f)
 return values.asList().chunked(7).mapIndexedNotNull { index,row ->
  if(row.any { !it.isFinite() }) return@mapIndexedNotNull null
  val cls=row[0].toInt();val score=row[1]
  if(row[0]!=cls.toFloat() || cls !in LABELS.indices || score<threshold || score>1f) return@mapIndexedNotNull null
  val b=Box(row[2],row[3],row[4],row[5]).clipped(w,h)
  if(b.width<=0 || b.height<=0) null else LayoutDetection(index,LABELS[cls],score,b,row[6].toInt())
 }.sortedWith(compareBy<LayoutDetection> { it.order }.thenBy { it.id })
}
data class FitTransform(val scale:Float,val x:Float,val y:Float) {
 fun map(b:Box)=Box(x+b.left*scale,y+b.top*scale,x+b.right*scale,y+b.bottom*scale)
 companion object { fun create(w:Int,h:Int,cw:Float,ch:Float):FitTransform { require(w>0 && h>0);val s=min(cw/w,ch/h);return FitTransform(s,(cw-w*s)/2,(ch-h*s)/2) } }
}
fun normalizedChannel(value:Int,channel:Int):Float=(value/255f-floatArrayOf(.485f,.456f,.406f)[channel])/floatArrayOf(.229f,.224f,.225f)[channel]
fun warmStats(times:List<Double>):String { require(times.size>=2); val warm=times.drop(1);return "First ${"%.0f".format(times.first())} ms · warm avg ${"%.0f".format(warm.average())} ms · min ${"%.0f".format(warm.min())} · max ${"%.0f".format(warm.max())} (${warm.size} warm runs)" }

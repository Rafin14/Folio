package dev.folio.scanner.pdfanalysis

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

enum class FigureFormat(val label:String,val mime:String,val extension:String) {
    JPEG("JPEG · original","image/jpeg","jpg"),VECTOR("PDF · vector","application/pdf","pdf"),PNG("PNG · rendered","image/png","png")
}
data class Figure(val id:String,val page:Int,val box:Box,val label:String,val format:FigureFormat,val objectIndex:Int=-1,val caption:String="") {
    fun json()=JSONObject().put("id",id).put("page",page).put("box",box.json()).put("label",label).put("format",format.name).put("objectIndex",objectIndex).put("caption",caption)
    companion object {fun parse(j:JSONObject)=Figure(j.getString("id"),j.getInt("page"),boxFrom(j.getJSONArray("box")),j.getString("label"),FigureFormat.valueOf(j.getString("format")),j.optInt("objectIndex",-1),j.optString("caption"))}
}
private fun Box.intersects(b:Box)=right>=b.left && left<=b.right && bottom>=b.top && top<=b.bottom
fun figureFormat(page:AnalysisPage,region:Box):Pair<FigureFormat,Int> {
    val objects=List(page.objects.length()) {page.objects.getJSONObject(it)}.filter {boxFrom(it.getJSONArray("box")).intersects(region)}
    if(objects.size==1 && objects.single().optBoolean("originalJpeg") && boxFrom(objects.single().getJSONArray("box")).overlap(region)>.97f) return FigureFormat.JPEG to objects.single().getInt("index")
    val vector=objects.isNotEmpty() && objects.any {it.getInt("type")==2} && page.annotations==0 && objects.all {obj ->
        val b=boxFrom(obj.getJSONArray("box"));obj.getInt("type") in listOf(1,2) && !obj.optBoolean("clipped") && b.left>=region.left-1 && b.right<=region.right+1 && b.top>=region.top-1 && b.bottom<=region.bottom+1
    }
    return (if(vector) FigureFormat.VECTOR else FigureFormat.PNG) to -1
}
fun reconcileFigures(page:AnalysisPage):List<Figure> {
    val result=mutableListOf<Figure>()
    fun retain(box:Box,label:String) {
        val clipped=box.clipped(page.width,page.height);if(clipped.width<8 || clipped.height<8 || result.any {it.box.overlap(clipped)>.7f}) return
        val (format,objectIndex)=figureFormat(page,clipped)
        val caption=page.regions.filter {it.label=="figure_title" && it.box.top>=clipped.bottom && it.box.top-clipped.bottom<page.height*.08 && it.box.right>clipped.left && it.box.left<clipped.right}.minByOrNull {it.box.top}?.text.orEmpty()
        result+=Figure("${page.index}-${result.size}",page.index,clipped,label,format,objectIndex,caption)
    }
    page.regions.filter {it.label in setOf("image","chart","header_image","footer_image","display_formula","seal")}.forEach {retain(it.box,it.label)}
    repeat(page.objects.length()) {i ->val obj=page.objects.getJSONObject(i);if(obj.getInt("type")==3) {
        val b=boxFrom(obj.getJSONArray("box"));if(result.none {it.box.overlap(b)>.5f || it.box.contains((b.left+b.right)/2,(b.top+b.bottom)/2)}) retain(b,"embedded image")
    }}
    return result
}
data class FigureOutput(val file:File,val format:FigureFormat)
fun extractFigure(pdf:PdfiumDocument,page:AnalysisPage,figure:Figure,folder:File,cancelled:()->Unit={}):FigureOutput {
    cancelled();val box=figure.box.clipped(page.width,page.height);require(box.width>=8 && box.height>=8)
    val safeFormat=figureFormat(page,box);val stem="figure-${figure.id}"
    if(safeFormat.first==FigureFormat.JPEG) {
        val file=File(folder,"$stem.jpg");pdf.originalJpeg(page.index,safeFormat.second,file);cancelled()
        val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeFile(file.path,options);check(options.outWidth>0 && options.outHeight>0)
        return FigureOutput(file,FigureFormat.JPEG)
    }
    if(safeFormat.first==FigureFormat.VECTOR) {
        val file=File(folder,"$stem.pdf")
        try {pdf.vectorRegion(page,box,file);PdfiumDocument(file).use {check(it.count==1)};cancelled();return FigureOutput(file,FigureFormat.VECTOR)}
        catch(_:IllegalStateException) {file.delete()} // Composite objects fall back truthfully to PNG.
    }
    val bitmap=pdf.render(page.index,4000)
    try {
        cancelled();val sx=bitmap.width.toFloat()/page.width;val sy=bitmap.height.toFloat()/page.height
        val x=floor(box.left*sx).toInt().coerceIn(0,bitmap.width-1);val y=floor(box.top*sy).toInt().coerceIn(0,bitmap.height-1)
        val width=(ceil(box.right*sx).toInt()-x).coerceIn(1,bitmap.width-x);val height=(ceil(box.bottom*sy).toInt()-y).coerceIn(1,bitmap.height-y)
        val crop=Bitmap.createBitmap(bitmap,x,y,width,height)
        try {val file=File(folder,"$stem.png");file.outputStream().use {check(crop.compress(Bitmap.CompressFormat.PNG,100,it))};cancelled();return FigureOutput(file,FigureFormat.PNG)}
        finally {if(crop!==bitmap) crop.recycle()}
    } finally {bitmap.recycle()}
}

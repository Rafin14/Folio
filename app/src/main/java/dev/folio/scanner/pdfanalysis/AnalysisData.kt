package dev.folio.scanner.pdfanalysis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

fun Box.json()=JSONArray(listOf(left,top,right,bottom))
fun boxFrom(a:JSONArray)=Box(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat())
fun Box.contains(x:Float,y:Float)=x>=left && x<=right && y>=top && y<=bottom
fun Box.overlap(other:Box):Float {
    val area=max(0f,min(right,other.right)-max(left,other.left))*max(0f,min(bottom,other.bottom)-max(top,other.top))
    return area/max(1f,width*height+other.width*other.height-area)
}
data class AnalysisRegion(val id:Int,val label:String,val box:Box,val order:Int,val text:String="",val fontSize:Double=11.0,val bold:Boolean=false,val native:Boolean=false) {
    fun json()=JSONObject().put("id",id).put("label",label).put("box",box.json()).put("order",order).put("text",text).put("fontSize",fontSize).put("bold",bold).put("native",native)
    companion object {fun parse(j:JSONObject)=AnalysisRegion(j.getInt("id"),j.getString("label"),boxFrom(j.getJSONArray("box")),j.getInt("order"),j.optString("text"),j.optDouble("fontSize",11.0),j.optBoolean("bold"),j.optBoolean("native"))}
}
data class AnalysisPage(val index:Int,val width:Int,val height:Int,val widthPt:Double,val heightPt:Double,val regions:List<AnalysisRegion>,val objects:JSONArray,val diagnostics:String,val characters:JSONArray=JSONArray(),val lines:JSONArray=JSONArray(),val annotations:Int=0,val mode:OcrMode=OcrMode.LAYOUT_AWARE,val request:String="") {
    fun json()=JSONObject().put("version",2).put("index",index).put("width",width).put("height",height).put("widthPt",widthPt).put("heightPt",heightPt).put("regions",JSONArray(regions.map { it.json() })).put("objects",objects).put("diagnostics",diagnostics).put("characters",characters).put("lines",lines).put("annotations",annotations).put("mode",mode.name).put("request",request)
    val text get()=regions.sortedBy { it.order }.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
    companion object {fun parse(j:JSONObject)=AnalysisPage(j.getInt("index"),j.getInt("width"),j.getInt("height"),j.getDouble("widthPt"),j.getDouble("heightPt"),List(j.getJSONArray("regions").length()) { AnalysisRegion.parse(j.getJSONArray("regions").getJSONObject(it)) },j.getJSONArray("objects"),j.optString("diagnostics"),j.optJSONArray("characters") ?: JSONArray(),j.optJSONArray("lines") ?: JSONArray(),j.optInt("annotations"),OcrMode.valueOf(j.optString("mode",OcrMode.LAYOUT_AWARE.name)),j.optString("request"))}
}

/** Native characters are assigned once, preventing native/OCR duplicate text in mixed pages. */
fun nativeRegion(chars:List<JSONObject>,region:LayoutDetection,used:MutableSet<Int>):AnalysisRegion {
    val selected=chars.indices.filter { i -> i !in used && boxFrom(chars[i].getJSONArray("box")).let { region.box.contains((it.left+it.right)/2,(it.top+it.bottom)/2) } }
    val text=buildString { selected.forEach { i -> val c=chars[i].getInt("unicode");if(Character.isValidCodePoint(c)) append(Character.toChars(c)) } }
    if(text.count(Char::isLetterOrDigit)<2) return AnalysisRegion(region.id,region.label,region.box,region.order)
    used+=selected
    val fonts=selected.map { chars[it].optDouble("size",11.0) }.filter { it.isFinite() && it>0 }
    return AnalysisRegion(region.id,region.label,region.box,region.order,text,fonts.average().takeIf { it.isFinite() } ?: 11.0,selected.any { chars[it].optBoolean("bold") },true)
}

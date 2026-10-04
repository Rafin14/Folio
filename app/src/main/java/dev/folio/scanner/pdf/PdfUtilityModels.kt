package dev.folio.scanner.pdf

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun pdfFileStem(name: String): String = name.substringBeforeLast('.', name).replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(120).ifBlank { "Document" }
fun splitGroups(text: String, count: Int): List<List<Int>> = if(text.isBlank()) (1..count).map { listOf(it) } else text.split(',').map { pageSelection(it,count) }.also { groups -> require(groups.flatten().distinct().size==groups.sumOf { it.size }) { "Choose each page once." } }
fun splitFilename(stem: String, pages: List<Int>): String = "$stem ${if(pages.size==1) "Page ${pages.single()}" else "Pages ${pages.first()}-${pages.last()}"}.pdf"
fun <T> moved(items:List<T>,from:Int,to:Int):List<T> { require(from in items.indices && to in items.indices); return items.toMutableList().apply { add(to,removeAt(from)) } }
data class InkPoint(val x:Float,val y:Float)
data class FolioPageChoice(val documentId:String,val pageId:String)
fun togglePageChoice(selected:List<FolioPageChoice>,page:FolioPageChoice,single:Boolean=false):List<FolioPageChoice> = if(page in selected) selected-page else if(single) listOf(page) else selected+page
data class PdfInk(val color:Int,val width:Float,val points:List<InkPoint>)
data class PdfNote(val text:String,val x:Float=.08f,val y:Float=.12f,val color:Int,val size:Float=.025f)
data class UtilityPage(val id:String=UUID.randomUUID().toString(),val source:Int,val rotation:Int=0,val replacement:String="",val ink:List<PdfInk> = emptyList(),val notes:List<PdfNote> = emptyList()) {
    fun rotated()=copy(rotation=(rotation+90)%360,ink=ink.map { it.copy(points=it.points.map { p -> InkPoint(1-p.y,p.x) }) },notes=notes.map { it.copy(x=1-it.y,y=it.x) })
    fun json()=JSONObject().put("id",id).put("source",source).put("rotation",rotation).put("replacement",replacement)
        .put("ink",JSONArray(ink.map { s -> JSONObject().put("color",s.color).put("width",s.width).put("points",JSONArray(s.points.map { JSONArray(listOf(it.x,it.y)) })) }))
        .put("notes",JSONArray(notes.map { n -> JSONObject().put("text",n.text).put("x",n.x).put("y",n.y).put("color",n.color).put("size",n.size) }))
    companion object {
        fun from(j:JSONObject)=UtilityPage(j.getString("id"),j.getInt("source"),j.getInt("rotation"),j.getString("replacement"),j.getJSONArray("ink").let { a -> List(a.length()) { i -> val s=a.getJSONObject(i); PdfInk(s.getInt("color"),s.getDouble("width").toFloat(),s.getJSONArray("points").let { p -> List(p.length()) { k -> p.getJSONArray(k).let { InkPoint(it.getDouble(0).toFloat(),it.getDouble(1).toFloat()) } } }) } },j.getJSONArray("notes").let { a -> List(a.length()) { i -> val n=a.getJSONObject(i); PdfNote(n.getString("text"),n.getDouble("x").toFloat(),n.getDouble("y").toFloat(),n.getInt("color"),n.getDouble("size").toFloat()) } })
    }
}

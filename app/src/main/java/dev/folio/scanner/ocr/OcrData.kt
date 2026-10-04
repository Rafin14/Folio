package dev.folio.scanner.ocr

import dev.folio.scanner.data.Page
import dev.folio.scanner.data.layout
import dev.folio.scanner.data.OcrResult
import dev.folio.scanner.backup.sha256
import org.json.JSONArray
import org.json.JSONObject

const val OCR_MODEL = "PP-OCRv6_small/d73e0058/5435fd74"
const val OCR_PREPROCESS = "folio-corrected-bgr-v1"
data class TextRegion(val text:String,val points:List<Double>,val confidence:Double)
fun regionsJson(regions:List<TextRegion>)=JSONArray(regions.map { JSONObject().put("text",it.text).put("points",JSONArray(it.points)).put("confidence",it.confidence) }).toString()
fun parseRegions(value:String):List<TextRegion> {
    require(value.length<=4*1024*1024)
    val a=JSONArray(value); require(a.length()<=3000)
    return List(a.length()) { i -> val r=a.getJSONObject(i); val p=r.getJSONArray("points")
        require(p.length()==8); val points=List(8) { p.getDouble(it) }; val score=r.getDouble("confidence")
        require(points.all { it.isFinite() && it in 0.0..1.0 } && score.isFinite() && score in 0.0..1.0)
        TextRegion(r.getString("text"),points,score)
    }
}
fun ocrRevision(page:Page,sourceHash:String)=sha256("$sourceHash|${page.crop}|${page.rotation}|${page.enhancement}|${page.width}|${page.height}${if(page.pageSize=="Original") "" else "|${page.layout()}"}".toByteArray())
/** Naming and order do not change inference input; publication must survive these mutations. */
fun sameOcrPage(current:Page?,snapshot:Page)=current!=null && current.trashedAt==null && current.id==snapshot.id && current.documentId==snapshot.documentId && current.originalImageUri==snapshot.originalImageUri && ocrRevision(current,"")==ocrRevision(snapshot,"")
fun validOcr(result:OcrResult?,page:Page)=result!=null && result.status=="complete" && result.language=="en" && result.engine=="PaddleOCR" && result.modelVersion==OCR_MODEL && result.preprocessingVersion==OCR_PREPROCESS && result.sourceHash.matches(Regex("[a-f0-9]{64}")) && result.revision==ocrRevision(page,result.sourceHash)
fun searchExpression(query:String):String=query.trim().split(Regex("[^\\p{L}\\p{N}]+" )).filter { it.isNotBlank() }.take(12).joinToString(" ") { "\"$it*\"" }
data class OcrSearchHit(val documentId:String,val pageId:String,val title:String,val position:Int,val snippet:String,val thumbnailUri:String="",val pageName:String?=null)
fun ocrJson(r:OcrResult)=JSONObject().put("pageId",r.pageId).put("text",r.text).put("modifiedAt",r.modifiedAt).put("regions",r.regions)
    .put("status",r.status).put("revision",r.revision).put("sourceHash",r.sourceHash).put("language",r.language).put("engine",r.engine)
    .put("modelVersion",r.modelVersion).put("preprocessingVersion",r.preprocessingVersion).put("width",r.width).put("height",r.height).put("processingTimeMs",r.processingTimeMs)
fun readOcr(o:JSONObject)=OcrResult(o.getString("pageId"),o.getString("text"),o.getLong("modifiedAt"),o.getString("regions"),o.getString("status"),o.getString("revision"),o.getString("sourceHash"),o.getString("language"),o.getString("engine"),o.getString("modelVersion"),o.getString("preprocessingVersion"),o.getInt("width"),o.getInt("height"),o.getLong("processingTimeMs"))

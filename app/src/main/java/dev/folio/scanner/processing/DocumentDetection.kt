package dev.folio.scanner.processing

import android.graphics.Bitmap
import kotlin.math.exp
import kotlin.math.hypot

enum class DetectionSource { LCNET, MANUAL }
data class DocumentDetectionResult(val corners: List<Corner>,val confidence: Float?,val inferenceTimeMs: Double,val source: DetectionSource)
interface DocumentCornerDetector: AutoCloseable {
    val diagnostics: DetectionDiagnostics get()=DetectionDiagnostics()
    fun detect(image: Bitmap): DocumentDetectionResult?
    override fun close() {}
}
data class DetectionDiagnostics(val provider: String="Not initialized",val loadMs: Double=0.0,val inferenceMs: Double=0.0,val totalMs: Double=0.0,
    val source: DetectionSource?=null,val confidence: Float?=null,val corners: List<Corner>?=null,val frames: Int=0,val memoryMb: Double=0.0,val fps: Double=0.0)

/** Match benchmark confidence and geometry validation, without extra crop heuristics. */
fun validatedDetection(result: DocumentDetectionResult?,width: Int,height: Int): DocumentDetectionResult? {
    if(result==null || width<2 || height<2 || result.corners.size!=4 || result.corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return null
    if(result.confidence!=null && (!result.confidence.isFinite() || result.confidence !in .3f..1f)) return null
    val ordered=try { Geometry.order(result.corners,width,height) } catch(_: IllegalArgumentException) { return null }
    return result.copy(corners=ordered)
}

/** Benchmark's time-dependent smoothing; coordinates are always in upright ViewPort space. */
class CornerSmoother {
    private var previous: List<Corner>? = null
    private var time = 0L
    fun reset() { previous = null; time = 0 }
    fun update(points: List<Corner>?, nowNanos: Long): List<Corner>? {
        if (points == null) { reset(); return null }
        val ordered = try { if(Geometry.valid(points)) points else Geometry.order(points) } catch(_: IllegalArgumentException) { reset(); return null }
        val old = previous
        val alpha = (1 - exp(-(nowNanos-time).coerceAtLeast(0)/180_000_000.0)).coerceIn(.1,1.0)
        val displacement = old?.let { ordered.indices.sumOf { i -> hypot(ordered[i].x-it[i].x,ordered[i].y-it[i].y) } / 4 }
        val next = if (old == null || displacement!! > .15) ordered else ordered.mapIndexed { i,p -> Corner(old[i].x+(p.x-old[i].x)*alpha,old[i].y+(p.y-old[i].y)*alpha) }
        time = nowNanos; previous = next
        return next
    }
}

fun heatmapPoint(x: Double,y: Double)=Corner((x+.5)/128,(y+.5)/128)
fun decodedHeatmapCorners(centroids: List<Corner>,peaks: List<Float>): DocumentDetectionResult? {
    if(centroids.size!=4 || peaks.size!=4 || peaks.any { !it.isFinite() || it !in .3f..1f }) return null
    val points=centroids.map { heatmapPoint(it.x,it.y) }
    val ordered=try { Geometry.order(points) } catch(_: IllegalArgumentException) { return null }
    return DocumentDetectionResult(ordered,peaks.min(),0.0,DetectionSource.LCNET)
}
/** Channel-major BGR /255; shared by native bitmap preprocessing and JVM tests. */
fun packLcNetBgr(pixels: IntArray,input: java.nio.FloatBuffer) {
    require(pixels.size==65536 && input.capacity()==196608)
    input.clear(); for(shift in intArrayOf(0,8,16)) for(pixel in pixels) input.put(((pixel shr shift) and 255)/255f); input.rewind()
}

/** CPU retry uses fresh session options; an unavailable XNNPACK EP never disables scanning. */
internal fun <T> initializeLcNetProvider(create: (Boolean)->T,onFallback: (Exception)->Unit): Pair<String,T> =
    try { "XNNPACK" to create(true) } catch(failure: Exception) { onFallback(failure); "CPU" to create(false) }

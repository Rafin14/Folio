package dev.folio.scanner.processing

import kotlin.math.*

data class Corner(val x: Double, val y: Double) {
    fun distance(other: Corner) = hypot(x - other.x, y - other.y)
}
object Geometry {
    val full = listOf(Corner(0.0, 0.0), Corner(1.0, 0.0), Corner(1.0, 1.0), Corner(0.0, 1.0))
    fun order(points: List<Corner>, width: Int = 1, height: Int = 1): List<Corner> {
        require(points.size == 4 && points.distinct().size == 4) { "Select four different corners." }
        require(points.all { it.x.isFinite() && it.y.isFinite() })
        val cx = points.sumOf { it.x } / 4
        val cy = points.sumOf { it.y } / 4
        val around = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        require(width > 0 && height > 0)
        // Normalized axes have different physical lengths on non-square captures.
        val first = around.indices.minBy { around[it].x * width + around[it].y * height }
        val result = List(4) { around[(first + it) % 4] }
        require(valid(result)) { "The corners do not form a document." }
        return result
    }
    fun valid(points: List<Corner>): Boolean {
        if (points.size != 4 || points.any { !it.x.isFinite() || !it.y.isFinite() }) return false
        val turns = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return turns.all { it > 0.00001 } && area(points) > 0.001
    }
    fun area(points: List<Corner>): Double = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i + 1) % 4]; a.x * b.y - a.y * b.x
    }) / 2
    fun output(points: List<Corner>, width: Int, height: Int): Pair<Int, Int> {
        require(valid(points))
        require(points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }) { "Corners must stay inside the image." }
        val pixel = points.map { Corner(it.x * (width - 1), it.y * (height - 1)) }
        return max(pixel[0].distance(pixel[1]), pixel[3].distance(pixel[2])).roundToInt().coerceAtLeast(2) to
            max(pixel[0].distance(pixel[3]), pixel[1].distance(pixel[2])).roundToInt().coerceAtLeast(2)
    }
}

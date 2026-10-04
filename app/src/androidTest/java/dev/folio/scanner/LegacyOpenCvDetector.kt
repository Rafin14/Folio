// Frozen pre-LCNet detector for historical regression/benchmark assertions only.
package dev.folio.scanner

import dev.folio.scanner.processing.*

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Bounded analysis only. Returned coordinates always refer to the original image. */
internal object LegacyOpenCvDetector {
    fun detect(bitmap: Bitmap): List<Corner>? {
        val mats = mutableListOf<Mat>()
        fun mat() = Mat().also { mats += it }
        val rgba = mat(); val rgb = mat(); val gray = mat(); val local = mat(); val edge = mat(); val support = mat(); val mask = mat(); val hsv = mat(); val lab = mat(); val channel = mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).also { mats += it }
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        try {
            Utils.bitmapToMat(bitmap, rgba)
            val scale = minOf(1.0, 640.0 / max(bitmap.width, bitmap.height))
            Imgproc.resize(rgba, rgba, Size(bitmap.width * scale, bitmap.height * scale))
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            clahe.apply(gray, local)
            // Low-threshold support measures boundaries, not area or interior text density.
            Imgproc.Canny(local, support, 18.0, 55.0)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV); Core.extractChannel(hsv, mask, 1)
            Imgproc.GaussianBlur(mask, mask, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(mask, edge, 18.0, 55.0); Core.bitwise_or(support, edge, support)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            for (index in 1..2) {
                Core.extractChannel(lab, channel, index); Imgproc.GaussianBlur(channel, channel, Size(5.0,5.0), 0.0)
                Imgproc.Canny(channel, edge, 12.0, 35.0); Core.bitwise_or(support, edge, support)
            }
            Imgproc.dilate(support, support, kernel)
            val pixels = ByteArray((support.total()).toInt()); support.get(0, 0, pixels)
            val width = gray.cols(); val height = gray.rows()
            var best: List<Corner>? = null; var bestScore = .56
            fun consider(raw: Array<Point>, contourArea: Double? = null) {
                val ordered = runCatching { Geometry.order(raw.map { Corner(it.x / (width - 1), it.y / (height - 1)) }) }.getOrNull() ?: return
                val pixel = ordered.map { Corner(it.x * (width - 1), it.y * (height - 1)) }
                val sides = pixel.indices.map { pixel[it].distance(pixel[(it + 1) % 4]) }
                val aspect = max(sides[0], sides[2]) / max(sides[1], sides[3])
                if (aspect !in .25..4.0 || sides.min() < min(width, height) * .09) return
                if (max(sides[0], sides[2]) / min(sides[0], sides[2]) > 2.8 || max(sides[1], sides[3]) / min(sides[1], sides[3]) > 2.8) return
                val cosines = pixel.indices.map { i ->
                    val a = pixel[(i + 3) % 4]; val b = pixel[i]; val c = pixel[(i + 1) % 4]
                    abs(((a.x - b.x) * (c.x - b.x) + (a.y - b.y) * (c.y - b.y)) / (a.distance(b) * c.distance(b)))
                }
                if (cosines.max() > .8) return
                val quadArea = Geometry.area(ordered)
                if (quadArea !in .045.. .97 || ordered.any { it.x !in 0.0..1.0 || it.y !in 0.0..1.0 }) return
                val fill = contourArea?.div(quadArea) ?: 1.0
                if (fill !in .7..1.12) return
                val edges = pixel.indices.map { i ->
                    val a = pixel[i]; val b = pixel[(i + 1) % 4]
                    (0..40).count { step ->
                        val x = (a.x + (b.x - a.x) * step / 40).roundToInt().coerceIn(0, width - 1)
                        val y = (a.y + (b.y - a.y) * step / 40).roundToInt().coerceIn(0, height - 1)
                        pixels[y * width + x].toInt() != 0
                    } / 41.0
                }
                if (edges.min() < (if (contourArea == null) .60 else .30) || edges.average() < .52) return
                val boundary = ordered.count { it.x < .015 || it.x > .985 || it.y < .015 || it.y > .985 } / 4.0
                val score = .40 * edges.average() + .20 * (1 - cosines.average()) + .18 * fill.coerceAtMost(1.0) + .16 * sqrt(quadArea) + .06 * edges.min() - .25 * boundary
                if (score > bestScore) { bestScore = score; best = ordered }
            }
            fun candidates(binary: Mat) {
                val contours = mutableListOf<MatOfPoint>(); val hierarchy = mat()
                try {
                    Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
                    Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                    // ponytail: bound contour work on cluttered frames; profile before increasing this cap.
                    contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(40).forEach { contour ->
                        val area = abs(Imgproc.contourArea(contour)) / (width * height)
                        if (area !in .045.. .97) return@forEach
                        val hullIndices = MatOfInt(); val hull = MatOfPoint2f(); val approx = MatOfPoint2f()
                        try {
                            Imgproc.convexHull(contour, hullIndices)
                            val points = contour.toArray(); hull.fromArray(*hullIndices.toArray().map { points[it] }.toTypedArray())
                            val perimeter = Imgproc.arcLength(hull, true)
                            for (epsilon in listOf(.012, .025, .045)) {
                                Imgproc.approxPolyDP(hull, approx, perimeter * epsilon, true)
                                if (approx.rows() != 4) continue
                                consider(approx.toArray(), area)
                            }
                        } finally { hullIndices.release(); hull.release(); approx.release() }
                    }
                } finally { contours.forEach { it.release() } }
            }
            // Raw luminance keeps faint edges; local contrast handles uneven lighting.
            Imgproc.Canny(gray, edge, 20.0, 65.0); candidates(edge)
            Imgproc.Canny(local, edge, 45.0, 130.0); candidates(edge)
            Imgproc.threshold(gray, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU); candidates(mask)
            Imgproc.threshold(local, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU); candidates(mask)
            // Color boundaries can disappear in luminance (colored paper on a table).
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV); Core.extractChannel(hsv, mask, 1)
            Imgproc.GaussianBlur(mask, mask, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(mask, edge, 20.0, 65.0); candidates(edge)
            // Lab separates colored documents from clutter even when luminance/saturation match.
            for (index in 1..2) {
                Core.extractChannel(lab, channel, index); Imgproc.GaussianBlur(channel, channel, Size(5.0,5.0), 0.0)
                Imgproc.Canny(channel, edge, 12.0, 35.0); candidates(edge)
                Imgproc.threshold(channel, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU); candidates(mask)
            }
            // Connected clutter can destroy contours. Intersect four supported boundary lines.
            if (bestScore >= .82) return best
            val segments = mat()
            Imgproc.HoughLinesP(support, segments, 1.0, PI / 180, 45, min(width,height) * .13, 12.0)
            data class Line(val a: Double, val b: Double, val c: Double, val length: Double)
            val lines = (0 until segments.rows()).map { i ->
                val p = segments.get(i,0); val length = hypot(p[2]-p[0],p[3]-p[1])
                var a = (p[1]-p[3])/length; var b = (p[2]-p[0])/length
                if (a < 0 || a == 0.0 && b < 0) { a = -a; b = -b }
                Line(a,b,-a*p[0]-b*p[1],length)
            }.sortedByDescending { it.length }.fold(mutableListOf<Line>()) { unique, line ->
                if (unique.size < 28 && unique.none { abs(it.a*line.a+it.b*line.b) > .995 && abs(it.c-line.c) < 8 }) unique += line
                unique
            }
            fun cross(a: Line, b: Line): Point? {
                val d = a.a*b.b-b.a*a.b
                if (abs(d) < .3) return null
                return Point((a.b*b.c-b.b*a.c)/d,(b.a*a.c-a.a*b.c)/d)
            }
            val pairs = mutableListOf<Pair<Line,Line>>()
            for (i in lines.indices) for (j in i+1 until lines.size) {
                val a=lines[i]; val b=lines[j]
                if (abs(a.a*b.a+a.b*b.b) > .93 && abs(a.c-b.c) > min(width,height)*.09) pairs += a to b
            }
            // ponytail: at most 28 lines; bounded pair intersections rather than an unbounded search.
            for (i in pairs.indices) for (j in i+1 until pairs.size) {
                val (a,b)=pairs[i]; val (c,d)=pairs[j]
                if (abs(a.a*c.a+a.b*c.b) > .65) continue
                val points = listOf(cross(a,c),cross(a,d),cross(b,d),cross(b,c))
                if (points.all { it != null }) consider(points.map { it!! }.toTypedArray())
            }
            return best
        } finally { clahe.collectGarbage(); mats.forEach { it.release() } }
    }
}

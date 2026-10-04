package dev.folio.scanner.data

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Physical paper dimensions are independent of bitmap resolution and PDF compression. */
data class PageLayout(val size: String = "Original", val fit: String = "Fit", val widthMm: Double = 0.0, val heightMm: Double = 0.0) {
    init {
        require(size in sizes && fit in listOf("Fit", "Fill")) { "Choose a supported page size and fit." }
        require(widthMm.isFinite() && heightMm.isFinite())
        if (size == "Custom") require(widthMm in 10.0..1000.0 && heightMm in 10.0..1000.0) { "Use dimensions between 10 and 1000 mm." }
    }
    val dimensions: Pair<Double, Double>? get() = when(size) {
        "A4" -> 210.0 to 297.0
        "A5" -> 148.0 to 210.0
        "Letter" -> 215.9 to 279.4
        "Legal" -> 215.9 to 355.6
        "Square" -> 210.0 to 210.0
        "Custom" -> widthMm to heightMm
        else -> null
    }?.let { if(size!="Custom" && widthMm>heightMm) it.second to it.first else it }
    fun canvas(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val d = dimensions ?: return width to height
        val ratio = d.first / d.second
        var w = if (fit == "Fit") maxOf(width.toDouble(), height * ratio) else minOf(width.toDouble(), height * ratio)
        var h = w / ratio
        val scale = minOf(1.0, 8192 / maxOf(w,h), sqrt(12_500_000.0 / (w*h)))
        w *= scale; h *= scale
        return w.roundToInt().coerceAtLeast(2) to h.roundToInt().coerceAtLeast(2)
    }
    companion object { val sizes = listOf("Original", "A4", "A5", "Letter", "Legal", "Square", "Custom") }
}

fun Page.layout() = PageLayout(pageSize, pageFit, pageWidthMm, pageHeightMm)
fun Page.label() = pageName?.takeIf { it.isNotBlank() } ?: "Page ${position + 1}"
fun pageName(value: String): String? = value.trim().takeIf { it.isNotEmpty() }?.also {
    require(it.length <= 120 && it.none { c -> c.isISOControl() }) { "Use a name with 120 characters or fewer and no control characters." }
}

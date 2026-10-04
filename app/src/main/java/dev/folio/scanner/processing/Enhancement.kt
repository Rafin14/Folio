package dev.folio.scanner.processing

data class Enhancement(
    val preset: String = "Original", val brightness: Double = 0.0, val contrast: Double = 1.0,
    val saturation: Double = 1.0, val sharpness: Double = 0.0, val threshold: Double = 12.0,
    val shadow: Double = 0.0
) {
    init {
        require(preset in presets)
        require(brightness.isFinite() && brightness in -80.0..80.0)
        require(contrast.isFinite() && contrast in .5..2.0)
        require(saturation.isFinite() && saturation in 0.0..2.0)
        require(sharpness.isFinite() && sharpness in 0.0..2.0)
        require(threshold.isFinite() && threshold in 0.0..30.0)
        require(shadow.isFinite() && shadow in 0.0..1.0)
    }
    fun encode() = listOf(preset, brightness, contrast, saturation, sharpness, threshold, shadow).joinToString("|")
    companion object {
        val presets = listOf("Original", "Auto", "Black & White", "Grayscale", "Color", "No Shadow", "Document", "Sharpen", "Lighten")
        fun decode(value: String): Enhancement = runCatching {
            val p = value.split('|')
            if (p.size == 1) Enhancement(p[0]) else Enhancement(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p.getOrNull(6)?.toDouble() ?: 0.0)
        }.getOrDefault(Enhancement())
    }
}

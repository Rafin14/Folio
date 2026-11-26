package dev.folio.scanner.processing

data class Enhancement(
    val preset: String = "Original", val brightness: Double = 0.0, val contrast: Double = 1.0,
    val saturation: Double = 1.0, val sharpness: Double = 0.0, val threshold: Double = 12.0,
    val shadow: Double = 0.0
) {
    val activePresets = if (preset == "Original") emptyList() else preset.split('+')

    init {
        require(activePresets.size == activePresets.distinct().size &&
            activePresets.all { it != "Original" && it in presets }) { "Invalid enhancement preset." }
        require(brightness.isFinite() && brightness in -80.0..80.0)
        require(contrast.isFinite() && contrast in .5..2.0)
        require(saturation.isFinite() && saturation in 0.0..2.0)
        require(sharpness.isFinite() && sharpness in 0.0..2.0)
        require(threshold.isFinite() && threshold in 0.0..30.0)
        require(shadow.isFinite() && shadow in 0.0..1.0)
    }

    val removesShadow get() = activePresets.any { it == "Auto" || it == "Document" || it == "No Shadow" }
    val usesThreshold get() = "Black & White" in activePresets || "Document" in activePresets
    val usesGrayscale get() = usesThreshold || "Grayscale" in activePresets
    val boostsColor get() = "Color" in activePresets
    val brightnessOffset get() = brightness + if ("Lighten" in activePresets) 24.0 else 0.0
    val effectiveSharpness get() = maxOf(sharpness, if ("Sharpen" in activePresets) .8 else 0.0)

    fun togglePreset(name: String): Enhancement {
        require(name in presets)
        val chosen = when (name) {
            "Original" -> emptyList()
            in activePresets -> activePresets - name
            else -> activePresets + name
        }
        return copy(preset = presets.filter { it in chosen }.joinToString("+").ifEmpty { "Original" })
    }

    fun encode() = listOf(preset, brightness, contrast, saturation, sharpness, threshold, shadow).joinToString("|")
    companion object {
        val presets = listOf("Original", "Auto", "Black & White", "Grayscale", "Color", "No Shadow", "Document", "Sharpen", "Lighten")
        fun parse(value: String): Enhancement {
            val p = value.split('|')
            require(p.size == 1 || p.size == 6 || p.size == 7) { "Invalid enhancement." }
            return if (p.size == 1) Enhancement(p[0]) else Enhancement(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toDouble(), p[5].toDouble(), p.getOrNull(6)?.toDouble() ?: 0.0)
        }

        fun decode(value: String): Enhancement = runCatching { parse(value) }.getOrDefault(Enhancement())
    }
}

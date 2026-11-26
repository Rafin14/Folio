package dev.folio.scanner.processing

import org.junit.Assert.*
import org.junit.Test

class EnhancementTest {
    @Test fun roundTripAndParameterBoundaries() {
        val settings = Enhancement("Document", -80.0, 2.0, 0.0, 2.0, 30.0)
        assertEquals(settings, Enhancement.decode(settings.encode()))
        assertEquals(Enhancement("Color",shadow=.6),Enhancement.decode(Enhancement("Color",shadow=.6).encode()))
        assertEquals(0.0,Enhancement.decode("Document|0.0|1.0|1.0|0.0|12.0").shadow,0.0)
        Enhancement.presets.forEach { assertEquals(it,Enhancement.decode(Enhancement(it).encode()).preset) }
        assertEquals(Enhancement(), Enhancement.decode("not a preset|NaN"))
        assertThrows(IllegalArgumentException::class.java) { Enhancement(contrast = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Enhancement(brightness = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { Enhancement(sharpness = 3.0) }
    }

    @Test fun stackedPresetsRoundTripToggleAndKeepManualAdjustments() {
        val original = Enhancement(brightness = 6.0)
        val stack = original.togglePreset("Lighten").togglePreset("Grayscale").togglePreset("Sharpen")
        assertEquals(listOf("Grayscale", "Sharpen", "Lighten"), stack.activePresets)
        assertEquals(stack, Enhancement.parse(stack.encode()))
        assertTrue(stack.usesGrayscale)
        assertEquals(30.0, stack.brightnessOffset, 0.0)
        assertEquals(.8, stack.effectiveSharpness, 0.0)
        assertFalse(stack.togglePreset("Grayscale").usesGrayscale)
        assertEquals(original, stack.togglePreset("Original"))
        assertThrows(IllegalArgumentException::class.java) { Enhancement.parse("Original+Sharpen") }
        assertThrows(IllegalArgumentException::class.java) { Enhancement.parse("Lighten+Lighten") }
        assertThrows(IllegalArgumentException::class.java) { Enhancement.parse("Lighten+Unknown") }
    }
}

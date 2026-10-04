package dev.folio.scanner

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.folio.scanner.ui.amoledColors
import org.junit.Assert.*
import org.junit.Test

class AmoledColorsTest {
    @Test fun amoledHasBlackCanvasReadableHierarchyAndPreservesDynamicAccents() {
        val base=darkColorScheme(primary=Color(0xFFA4D9CB))
        val colors=amoledColors(base)
        assertEquals(Color.Black,colors.background); assertEquals(Color.Black,colors.surface)
        assertEquals(base.primary,colors.primary)
        assertEquals(base.secondary,colors.secondary); assertEquals(base.tertiary,colors.tertiary)
        assertEquals(base.surfaceContainer,colors.surfaceContainer); assertEquals(base.outline,colors.outline)
        assertEquals(base.onSurface,colors.onSurface); assertEquals(base.outlineVariant,colors.outlineVariant)
        val alternate=darkColorScheme(primary=Color(0xFFFFB59D),outline=Color(0xFFDD9988))
        assertEquals(alternate.primary,amoledColors(alternate).primary)
        assertEquals(alternate.outline,amoledColors(alternate).outline)
        val surfaces=listOf(colors.background,colors.surfaceContainerLow,colors.surfaceContainer,colors.surfaceContainerHigh,colors.surfaceContainerHighest)
        assertTrue(surfaces.zipWithNext().all { (a,b) -> a.luminance()<b.luminance() })
        for(surface in surfaces) {
            assertTrue((colors.onSurface.luminance()+.05f)/(surface.luminance()+.05f)>4.5f)
            assertTrue((colors.onSurfaceVariant.luminance()+.05f)/(surface.luminance()+.05f)>4.5f)
        }
    }
}

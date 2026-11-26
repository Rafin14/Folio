package dev.folio.scanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal data class FolioBlur(
    val enabled: Boolean = true,
    val strength: Int = 100,
    val translucency: Int = 50
) {
    val multiplier: Float get() = strength / 100f

    fun opacity(default: Float, minimum: Float = .32f): Float {
        val amount = translucency / 50f
        return if (amount <= 1f) 1f + (default - 1f) * amount
        else default + (minimum.coerceAtMost(default) - default) * (amount - 1f)
    }
}

internal val LocalFolioBlur = compositionLocalOf { FolioBlur() }

@Composable
fun FolioTheme(mode: String = "System", cameraOverlay: Boolean = false, blurEnabled: Boolean = true,
    blurStrength: Int = 100, translucency: Int = 50, content: @Composable () -> Unit) {
    val dark = mode == "Dark" || mode == "AMOLED" || mode == "System" && isSystemInDarkTheme()
    val view = LocalView.current
    val base = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(view.context) else dynamicLightColorScheme(view.context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    val colors = if(mode == "AMOLED") amoledColors(base) else base
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            if(android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = mode != "AMOLED"
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark && !cameraOverlay
                isAppearanceLightNavigationBars = !dark && !cameraOverlay
            }
        }
    }
    MaterialTheme(colorScheme = colors,
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
        )) {
        CompositionLocalProvider(LocalFolioBlur provides FolioBlur(blurEnabled, blurStrength, translucency), content = content)
    }
}

internal fun amoledColors(base: ColorScheme): ColorScheme = base.copy(
    background=Color.Black, surface=Color.Black, surfaceDim=Color.Black,
    surfaceContainerLowest=Color.Black)

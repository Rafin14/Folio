package dev.folio.scanner.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RULER_DIVISIONS = 12
private const val NEUTRAL_RADIUS = 1f / (RULER_DIVISIONS * 4)

internal fun rulerHaptic(previous: Float, next: Float, neutral: Float, steps: Int): HapticFeedbackType? {
    if (previous == next) return null
    val atEnd = next == 0f || next == 1f
    val nearNeutral = abs(next - neutral) <= NEUTRAL_RADIUS
    val wasNearNeutral = abs(previous - neutral) <= NEUTRAL_RADIUS
    val enteredNeutral = !wasNearNeutral && nearNeutral
    val crossedNeutral = !wasNearNeutral && !nearNeutral &&
        (previous < neutral && next > neutral || previous > neutral && next < neutral)
    if (atEnd || enteredNeutral || crossedNeutral) return HapticFeedbackType.SegmentTick
    val divisions = if (steps > 0) steps + 1 else RULER_DIVISIONS
    return HapticFeedbackType.SegmentFrequentTick.takeIf {
        (previous * divisions).roundToInt() != (next * divisions).roundToInt()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioRulerDial(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutral: Float,
    change: (Float) -> Unit,
    finished: (() -> Unit)? = null,
    enabled: Boolean = true,
    steps: Int = 0,
    displayValue: String = "%.2f".format(Locale.US, value),
    accessibilityLabel: String = label
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var lastTick by remember(accessibilityLabel) { mutableLongStateOf(-48L) }
    val accent = if (enabled) colors.primary else colors.onSurface.copy(alpha = .38f)
    val tick = colors.onSurfaceVariant.copy(alpha = if (enabled) .56f else .30f)
    val selected = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val reference = ((neutral - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, color = accent, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = value, onValueChange = { next ->
                val fraction = ((next - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                val tickType = rulerHaptic(selected, fraction, reference, steps)
                val feedback = if (Build.VERSION.SDK_INT >= 34) tickType
                    else HapticFeedbackType.VirtualKey.takeIf { tickType == HapticFeedbackType.SegmentTick }
                val now = SystemClock.uptimeMillis()
                if (enabled && feedback != null && now - lastTick >= 48L) {
                    lastTick = now
                    haptic.performHapticFeedback(feedback)
                }
                change(next)
            }, valueRange = range, onValueChangeFinished = finished,
            enabled = enabled, steps = steps, modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = accessibilityLabel
            },
            thumb = {
                Box(Modifier.size(width = 6.dp, height = 26.dp).background(accent, CircleShape))
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                    val baseline = size.height - 4.dp.toPx()
                    drawLine(tick, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
                    fun position(fraction: Float) = size.width *
                        if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
                    val start = position(reference)
                    val end = position(selected)
                    drawLine(accent, Offset(start, baseline), Offset(end, baseline), 2.dp.toPx(), StrokeCap.Round)
                    for (index in 0..RULER_DIVISIONS) {
                        val x = size.width * index / RULER_DIVISIONS
                        val length = if (index % 3 == 0) 10.dp else 6.dp
                        drawLine(tick, Offset(x, baseline - length.toPx()), Offset(x, baseline), 1.dp.toPx())
                    }
                    drawLine(accent, Offset(start, baseline - 14.dp.toPx()), Offset(start, baseline), 1.5.dp.toPx())
                }
            }
        )
    }
}

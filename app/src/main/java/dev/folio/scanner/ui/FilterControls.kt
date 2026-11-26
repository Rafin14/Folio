package dev.folio.scanner.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import dev.folio.scanner.processing.Enhancement

internal class FilterEdits(initial: String) {
    private var steps by mutableStateOf(listOf(initial))
    private var position by mutableIntStateOf(0)

    var value by mutableStateOf(initial)
        private set

    val settings get() = Enhancement.decode(value)
    val canUndo get() = position > 0
    val canRedo get() = position < steps.lastIndex

    fun initialize(saved: String) {
        if (value.isNotEmpty()) return
        value = Enhancement.decode(saved).encode()
        steps = listOf(value)
    }

    fun preview(settings: Enhancement) {
        value = settings.encode()
    }

    fun apply(settings: Enhancement) {
        preview(settings)
        commit()
    }

    fun commit() {
        if (value == steps[position]) return
        steps = (steps.take(position + 1) + value).takeLast(40)
        position = steps.lastIndex
    }

    fun undo() {
        if (!canUndo) return
        position--
        value = steps[position]
    }

    fun redo() {
        if (!canRedo) return
        position++
        value = steps[position]
    }

    companion object {
        val Saver = listSaver<FilterEdits, Any>(
            save = { listOf(it.position, it.value) + it.steps },
            restore = { saved ->
                FilterEdits(saved[1] as String).apply {
                    position = saved[0] as Int
                    steps = saved.drop(2).map { it as String }
                }
            }
        )
    }
}

@Composable
internal fun rememberFilterEdits(key: Any, initial: String = Enhancement().encode()) =
    rememberSaveable(key, saver = FilterEdits.Saver) { FilterEdits(initial) }

@Composable
internal fun FilterPicker(edits: FilterEdits, enabled: Boolean = true, showHistory: Boolean = true, capsule: Boolean = false) {
    val scroll = rememberScrollState()
    val settings = edits.settings
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).filterScrollIndicator(scroll, horizontal = true)
                .horizontalScroll(scroll).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (capsule) 6.dp else 8.dp)
        ) {
            Enhancement.presets.forEach { preset ->
                FilterChip(
                    selected = if (preset == "Original") settings.activePresets.isEmpty() else preset in settings.activePresets,
                    onClick = { edits.apply(edits.settings.togglePreset(preset)) },
                    label = { Text(preset, fontSize = if (capsule) 13.sp else TextUnit.Unspecified) },
                    shape = if (capsule) CircleShape else FilterChipDefaults.shape,
                    enabled = enabled
                )
            }
        }
        if (showHistory) {
            Spacer(Modifier.width(6.dp))
            FilterHistoryControls(edits, enabled)
        }
    }
}

@Composable
internal fun FilterHistoryControls(edits: FilterEdits, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val buttonColors = IconButtonDefaults.iconButtonColors(
        containerColor = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        disabledContentColor = colors.onSurface.copy(alpha = .38f)
    )
    FolioGlassSurface(shape = CircleShape, raised = false) {
        Row(Modifier.padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = edits::undo, enabled = enabled && edits.canUndo,
                modifier = Modifier.size(48.dp), colors = buttonColors) {
                Icon(Icons.AutoMirrored.Outlined.Undo, "Undo filter edit", Modifier.size(22.dp))
            }
            VerticalDivider(Modifier.height(20.dp), color = colors.outlineVariant)
            IconButton(onClick = edits::redo, enabled = enabled && edits.canRedo,
                modifier = Modifier.size(48.dp), colors = buttonColors) {
                Icon(Icons.AutoMirrored.Outlined.Redo, "Redo filter edit", Modifier.size(22.dp))
            }
        }
    }
}

@Composable
internal fun Modifier.filterScrollIndicator(scroll: ScrollState, horizontal: Boolean = false): Modifier {
    val colors = MaterialTheme.colorScheme
    val emphasis = animateFloatAsState(if (scroll.isScrollInProgress) 1f else 0f, tween(160), label = "Scroll indicator")
    return drawWithContent {
        drawContent()
        val max = scroll.maxValue
        val length = if (horizontal) size.width else size.height
        if (max <= 0 || max == Int.MAX_VALUE || length <= 0f) return@drawWithContent
        val inset = 5.dp.toPx().coerceAtMost(length / 4)
        val track = length - 2 * inset
        val thumb = (track * (length / (length + max))).coerceIn(minOf(20.dp.toPx(), track), track)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val fraction = (scroll.value.toFloat() / max).coerceIn(0f, 1f)
        val start = inset + (track - thumb) * if (horizontal && rtl) (1f - fraction) else fraction
        val thickness = 3.dp.toPx()
        fun point(axis: Float) = if (horizontal) Offset(axis, size.height - inset - thickness / 2)
            else Offset(if (rtl) inset - thickness / 2 else size.width - inset - thickness / 2, axis)
        fun barSize(span: Float) = if (horizontal) Size(span, thickness) else Size(thickness, span)
        val corner = CornerRadius(thickness / 2)
        drawRoundRect(colors.outlineVariant.copy(alpha = .48f), point(inset), barSize(track), corner)
        drawRoundRect(colors.primary.copy(alpha = .56f + .44f * emphasis.value), point(start), barSize(thumb), corner)
    }
}

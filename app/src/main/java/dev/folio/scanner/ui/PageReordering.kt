package dev.folio.scanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

internal fun galleryEdgeSpeed(y: Float, bottom: Float, edge: Float, maxSpeed: Float): Float {
    val band = edge.coerceAtMost(bottom / 2f)
    if (band <= 0f) return 0f
    val top = (1f - y / band).coerceIn(0f, 1f)
    val end = (1f - (bottom - y) / band).coerceIn(0f, 1f)
    return (end * end - top * top) * maxSpeed
}

private suspend fun scrollWhileReordering(
    state: ScrollableState,
    velocity: () -> Float,
    updateTarget: () -> Unit
) {
    var previous = withFrameNanos { it }
    while (true) {
        val frame = withFrameNanos { it }
        val seconds = ((frame - previous) / 1_000_000_000f).coerceAtMost(.032f)
        previous = frame
        val delta = velocity() * seconds
        if (delta != 0f && state.scrollBy(delta) != 0f) updateTarget()
    }
}

private fun LazyGridItemInfo.galleryBounds(topPadding: Int) = Rect(
    offset.x.toFloat(), offset.y + topPadding.toFloat(),
    offset.x + size.width.toFloat(), offset.y + topPadding + size.height.toFloat()
)

@Composable
internal fun ReorderablePageGrid(
    state: LazyGridState,
    order: List<String>,
    bottomOverlay: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    select: (String) -> Unit = {},
    drop: (List<String>) -> Unit,
    preview: @Composable (String) -> Unit,
    content: @Composable (String?, String?) -> Unit
) {
    val latestOrder by rememberUpdatedState(order)
    val latestSelect by rememberUpdatedState(select)
    val latestDrop by rememberUpdatedState(drop)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val inset = with(density) { bottomOverlay.toPx() }
    val edge = with(density) { 96.dp.toPx() }
    val speed = with(density) { 850.dp.toPx() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<String?>(null) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    fun bottom() = state.layoutInfo.viewportSize.height - inset
    fun updateTarget() {
        val info = state.layoutInfo
        val y = finger.y.coerceIn(0f, bottom().coerceAtLeast(0f))
        val point = Offset(finger.x, y)
        target = info.visibleItemsInfo.asSequence().map { it to it.galleryBounds(info.beforeContentPadding) }
            .filter { (_, bounds) -> bounds.bottom > 0f && bounds.top < bottom() }
            .minByOrNull { (_, bounds) -> (bounds.center - point).getDistanceSquared() }
            ?.first?.key as? String ?: target
    }
    LaunchedEffect(dragged, edge, speed, inset) {
        if (dragged != null) scrollWhileReordering(
            state, { if (dragged == null) 0f else galleryEdgeSpeed(finger.y, bottom(), edge, speed) }
        ) { if (dragged != null) updateTarget() }
    }
    Box(modifier.clipToBounds().pointerInput(enabled, state, inset) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.y >= bottom()) return@awaitEachGesture
            val info = state.layoutInfo
            val id = info.visibleItemsInfo.firstOrNull {
                it.galleryBounds(info.beforeContentPadding).contains(down.position)
            }?.key as? String ?: return@awaitEachGesture
            val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            try {
                while (true) {
                    // Take the held pointer before the nested grid can turn its move into a scroll.
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == hold.id } ?: break
                    if (change.isConsumed) break
                    change.consume()
                    if (!change.pressed) {
                        val from = latestOrder.indexOf(id)
                        val to = latestOrder.indexOf(target)
                        if (dragged == null) latestSelect(id)
                        else if (from >= 0 && to >= 0 && from != to) latestDrop(dev.folio.scanner.pdf.moved(latestOrder, from, to))
                        break
                    }
                    finger = change.position
                    if (dragged == null && (finger - hold.position).getDistance() > viewConfiguration.touchSlop) {
                        dragged = id
                        target = id
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                    if (dragged != null) updateTarget()
                }
            } finally {
                dragged = null
                target = null
            }
        }
    }) {
        content(dragged, target)
        dragged?.let { id ->
            FolioGlassSurface(
                Modifier.align(AbsoluteAlignment.TopLeft).absoluteOffset {
                    val width = 76.dp.roundToPx()
                    val height = 98.dp.roundToPx()
                    val x = (finger.x - width / 2).roundToInt().coerceIn(0, (state.layoutInfo.viewportSize.width - width).coerceAtLeast(0))
                    val y = (finger.y - height - 12.dp.toPx()).roundToInt().coerceIn(0, (bottom().roundToInt() - height).coerceAtLeast(0))
                    IntOffset(x, y)
                }.size(76.dp, 98.dp), shape = MaterialTheme.shapes.medium
            ) { Box(Modifier.fillMaxSize().padding(4.dp)) { preview(id) } }
        }
    }
}

@Composable
internal fun ReorderList(
    order: List<String>,
    label: (String) -> String,
    scroll: ScrollableState,
    viewport: () -> Rect,
    trailing: @Composable (String) -> Unit = {},
    drop: (List<String>) -> Unit
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    val latestOrder by rememberUpdatedState(order)
    val latestDrop by rememberUpdatedState(drop)
    val latestViewport by rememberUpdatedState(viewport)
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 96.dp.toPx() }
    val speed = with(density) { 850.dp.toPx() }
    var target by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var point by remember { mutableStateOf(Offset.Zero) }

    fun updateTarget() {
        val visible = latestViewport()
        target = bounds.entries.asSequence().filter { (_, row) -> row.overlaps(visible) }
            .minByOrNull { (_, row) -> kotlin.math.abs(row.center.y - point.y) }?.key ?: target
    }
    LaunchedEffect(order) { bounds.keys.retainAll(order.toSet()) }
    LaunchedEffect(dragging, scroll, edge, speed) {
        if (dragging != null) scrollWhileReordering(scroll, {
            val visible = latestViewport()
            if (dragging == null) 0f else galleryEdgeSpeed(point.y - visible.top, visible.height, edge, speed)
        }) { if (dragging != null) updateTarget() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { id ->
            key(id) {
                Surface(
                    Modifier.fillMaxWidth().onGloballyPositioned { bounds[id] = it.boundsInRoot() },
                    color = if (target == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(if (target == id) 2.dp else 1.dp,
                        if (target == id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = if (dragging == id) 8.dp else 0.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var center by remember { mutableStateOf(Offset.Zero) }
                        Icon(Icons.Outlined.DragHandle, null, Modifier.size(48.dp)
                            .onGloballyPositioned { center = it.boundsInRoot().center }
                            .semantics { contentDescription = "Drag to reorder $id" }
                            .pointerInput(id) {
                                fun clear() { dragging = null; target = null }
                                try {
                                    detectDragGestures(
                                        onDragStart = {
                                            point = center
                                            dragging = id
                                            target = id
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDragCancel = ::clear,
                                        onDragEnd = {
                                            val from = latestOrder.indexOf(id)
                                            val to = latestOrder.indexOf(target)
                                            clear()
                                            if (from >= 0 && to >= 0 && from != to)
                                                latestDrop(dev.folio.scanner.pdf.moved(latestOrder, from, to))
                                        }
                                    ) { change, amount ->
                                        change.consume()
                                        point += amount
                                        updateTarget()
                                    }
                                } finally {
                                    if (dragging == id) clear()
                                }
                            }.padding(12.dp))
                        Text(label(id), Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 8.dp),
                            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium)
                        trailing(id)
                    }
                }
            }
        }
    }
}

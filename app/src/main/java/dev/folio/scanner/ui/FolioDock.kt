package dev.folio.scanner.ui

import android.os.Build
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import kotlin.math.sin

internal class DockBackdrop(val layer: GraphicsLayer, val sampleScale: Float, val sampleWeight: () -> Float) {
    var origin by mutableStateOf(Offset.Unspecified)
        private set
    var ready by mutableStateOf(false)
        private set

    val source: Modifier = Modifier
        .onPlaced { origin = it.positionInRoot() }
        .drawWithContent {
            if (sampleWeight() == 0f) drawContent()
            else {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
                // A sampler can draw before the source layer has been recorded.
                if (!ready && layer.size.width > 0) ready = true
            }
        }
}

@Composable
internal fun rememberDockBackdrop(visible: Boolean, sampleScale: Float = 1f,
    sampleWeight: () -> Float = { 1f }): DockBackdrop? {
    val glass = LocalFolioBlur.current
    if (!visible || !glass.enabled || glass.translucency == 0 || Build.VERSION.SDK_INT < 31) return null
    val layer = rememberGraphicsLayer()
    return remember(layer, sampleScale, sampleWeight) { DockBackdrop(layer, sampleScale, sampleWeight) }
}

@Composable
internal fun Modifier.dockContentPadding(padding: PaddingValues, dockVisible: Boolean): Modifier {
    if (!dockVisible) return padding(padding)
    val direction = LocalLayoutDirection.current
    return padding(
        start = padding.calculateStartPadding(direction),
        end = padding.calculateEndPadding(direction),
        top = padding.calculateTopPadding()
    )
}

@Composable
internal fun FolioGlassSurface(
    modifier: Modifier = Modifier,
    backdrop: DockBackdrop? = null,
    camera: Boolean = false,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    raised: Boolean = true,
    refract: Boolean = false,
    tintOpacity: Float = if (refract) .42f else .45f,
    showRim: Boolean = true,
    sampleScale: Float = backdrop?.sampleScale ?: 1f,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalFolioBlur.current
    val tone = if (camera) Color.Black else colors.surfaceContainerHigh
    Box(modifier.shadow(if (raised) 2.dp else 0.dp, shape, clip = false).clip(shape)
        .background(when {
            camera -> tone.copy(alpha = glass.opacity(.70f, .52f))
            backdrop == null -> tone.copy(alpha = glass.opacity(.96f, .82f))
            else -> colors.surface
        })) {
        if (backdrop != null) {
            var position by remember { mutableStateOf(Offset.Unspecified) }
            val radius = with(LocalDensity.current) { (if (refract) 6.dp.toPx() else 12.dp.toPx()) * glass.multiplier * sampleScale }
            val blurEffect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
            Box(Modifier.matchParentSize()
                .onPlaced { position = it.positionInRoot() }
                .then(if (sampleScale == 1f) Modifier else Modifier.layout { measurable, constraints ->
                    val width = constraints.maxWidth
                    val height = constraints.maxHeight
                    val sample = measurable.measure(Constraints.fixed(
                        (width * sampleScale).roundToInt(), (height * sampleScale).roundToInt()
                    ))
                    // Blur a smaller surface while keeping the document layer full resolution.
                    layout(width, height) {
                        sample.placeWithLayer(0, 0) {
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = 1f / sampleScale
                            scaleY = 1f / sampleScale
                        }
                    }
                })
                .graphicsLayer {
                    alpha = backdrop.sampleWeight()
                    renderEffect = if (alpha == 0f) null
                    else if (refract && Build.VERSION.SDK_INT >= 33 && size.width > 0f && size.height > 0f) {
                        dockRefraction(size.width, size.height, radius, 17.dp.toPx() * sampleScale, 8.dp.toPx() * sampleScale)
                    } else blurEffect
                }
                .drawBehind {
                    if (backdrop.sampleWeight() > 0f && backdrop.ready && backdrop.origin != Offset.Unspecified && position != Offset.Unspecified) {
                        val offset = backdrop.origin - position
                        scale(sampleScale, sampleScale, pivot = Offset.Zero) {
                            translate(offset.x, offset.y) { drawLayer(backdrop.layer) }
                        }
                    }
                })
        }
        val top = when {
            camera -> Color.White.copy(alpha = .08f)
            backdrop == null -> Color.Transparent
            else -> tone.copy(alpha = glass.opacity(tintOpacity))
        }
        val bottom = if (camera || backdrop == null) Color.Transparent else tone.copy(alpha = glass.opacity(tintOpacity + .12f))
        Box(Modifier.matchParentSize()
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .then(if (showRim) Modifier.border(1.dp, folioGlassRim(), shape) else Modifier))
        Box(content = content)
    }
}

@Composable
internal fun folioGlassRim(): Brush {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .35f
    return Brush.verticalGradient(listOf(
        if (dark) Color.White.copy(alpha = .32f) else colors.outline.copy(alpha = .42f),
        colors.outlineVariant.copy(alpha = .35f),
        if (dark) Color.White.copy(alpha = .18f) else colors.outline.copy(alpha = .28f)
    ))
}

@RequiresApi(33)
private fun dockRefraction(width: Float, height: Float, blur: Float, edge: Float, bend: Float): androidx.compose.ui.graphics.RenderEffect {
    val shader = RuntimeShader("""
        uniform shader backdrop;
        uniform float2 bounds;
        uniform float edge;
        uniform float bend;
        half4 main(float2 p) {
            float radius = min(bounds.x, bounds.y) * 0.5;
            float2 normal = p - clamp(p, float2(radius), bounds - radius);
            float distance = length(normal);
            float depth = clamp((radius - distance) / edge, 0.0, 1.0);
            float2 shift = normal / max(distance, 1.0) * sin(depth * 3.14159) * bend;
            return backdrop.eval(p - shift);
        }
    """.trimIndent())
    shader.setFloatUniform("bounds", width, height)
    shader.setFloatUniform("edge", edge)
    shader.setFloatUniform("bend", bend)
    return RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(shader, "backdrop"),
        RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP)
    ).asComposeRenderEffect()
}

@Composable
internal fun FolioActionTray(
    modifier: Modifier = Modifier,
    backdrop: DockBackdrop? = null,
    camera: Boolean = false,
    horizontalPadding: androidx.compose.ui.unit.Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 6.dp), contentAlignment = Alignment.Center) {
        FolioGlassSurface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), backdrop, camera) {
            Column(Modifier.fillMaxWidth().padding(8.dp), content = content)
        }
    }
}

@Composable
internal fun FolioDock(
    workspaceSelected: Boolean,
    enabled: Boolean,
    backdrop: DockBackdrop?,
    lensPosition: State<Float>?,
    library: () -> Unit,
    workspace: () -> Unit,
    newDocument: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val shape = CircleShape
    val haptic = LocalHapticFeedback.current
    val selectionFeedback = if (Build.VERSION.SDK_INT >= 34) HapticFeedbackType.SegmentTick else HapticFeedbackType.VirtualKey
    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.widthIn(max = 320.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (newDocument != null) {
                FolioGlassSurface(Modifier.align(Alignment.End), backdrop, shape = shape, refract = true) {
                    FilledTonalButton(
                        enabled = enabled,
                        modifier = Modifier.padding(3.dp).heightIn(min = 48.dp)
                            .semantics { contentDescription = "New document" },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = colors.primaryContainer.copy(alpha = if (backdrop == null) 1f else .81f),
                            contentColor = colors.onPrimaryContainer
                        ),
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            newDocument()
                        }
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New document")
                    }
                }
            }
            FolioGlassSurface(Modifier.fillMaxWidth(), backdrop, shape = shape, raised = false, refract = true) {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp)) {
                    val tabWidth = maxWidth / 2
                    val lensWidth = (tabWidth - 4.dp).coerceAtMost(130.dp)
                    val dark = colors.surface.luminance() < .35f
                    val lensRim = Brush.verticalGradient(listOf(
                        if (dark) Color.White.copy(alpha = .55f) else colors.outline.copy(alpha = .42f),
                        colors.primary.copy(alpha = .11f),
                        if (dark) Color.White.copy(alpha = .24f) else colors.outline.copy(alpha = .24f)
                    ))
                    val lensFill = Brush.verticalGradient(listOf(
                        colors.primary.copy(alpha = if (dark) .30f else .22f),
                        colors.primary.copy(alpha = if (dark) .16f else .10f)
                    ))
                    val direction = if (LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Ltr) 1 else -1
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.width(lensWidth).fillMaxHeight()
                            .graphicsLayer {
                                val position = lensPosition?.value ?: if (workspaceSelected) 0f else 1f
                                translationX = direction * ((tabWidth - lensWidth) / 2 + tabWidth * position).toPx()
                                val travel = sin(position * Math.PI).toFloat()
                                scaleX = 1f + .10f * travel
                                scaleY = 1f - .05f * travel
                            }
                            .background(lensFill, shape)
                            .border(1.dp, lensRim, shape))
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        DockItem("PDF workspace", Icons.Outlined.PictureAsPdf, Modifier.weight(1f), enabled, workspaceSelected) {
                            if (!workspaceSelected) {
                                haptic.performHapticFeedback(selectionFeedback)
                                workspace()
                            }
                        }
                        DockItem("Library", Icons.Outlined.Folder, Modifier.weight(1f), enabled, !workspaceSelected) {
                            if (workspaceSelected) {
                                haptic.performHapticFeedback(selectionFeedback)
                                library()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockItem(
    label: String,
    icon: ImageVector,
    modifier: Modifier,
    enabled: Boolean,
    selected: Boolean,
    action: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .97f else 1f, tween(120), label = "Dock press")
    val foreground by animateColorAsState(if (selected) colors.primary else colors.onSurface, tween(180), label = "Tab tint")
    Column(
        modifier.heightIn(min = 56.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(CircleShape)
            .selectable(selected, interactionSource = interactions, indication = ripple(), enabled = enabled, role = Role.Tab, onClick = action)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val tint = foreground.copy(alpha = if (enabled) 1f else .38f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Text(label, color = tint, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

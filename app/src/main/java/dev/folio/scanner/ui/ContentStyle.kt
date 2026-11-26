package dev.folio.scanner.ui

import android.os.Build
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.annotation.RequiresApi
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One outline treatment for document surfaces; selection keeps its stronger border. */
@Composable
internal fun Modifier.contentOutline() = border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioTopBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val edge = colors.outlineVariant.copy(alpha = .45f)
    TopAppBar(
        title = title, navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor =
            colors.surfaceContainerLow.copy(alpha = LocalFolioBlur.current.opacity(.88f, .75f))),
        modifier = Modifier.drawWithContent {
            drawContent()
            drawLine(edge, Offset(0f, size.height - .5f), Offset(size.width, size.height - .5f))
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioSheet(
    onDismissRequest: () -> Unit,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    blurRadius: Int = 20,
    animateBlur: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val translucent = folioWindowBlurEnabled()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val sheetBounds = if (animateBlur && translucent) remember { mutableStateOf(IntSize.Zero) } else null
    val blurProgress = sheetBounds?.let { bounds ->
        remember(sheetState, bounds) {
            derivedStateOf {
                val (sheetHeight, windowHeight) = bounds.value
                if (sheetHeight == 0) 0f
                else {
                    // Material moves the sheet in a layer. Read the drag offset rather than layout callbacks.
                    val top = runCatching { sheetState.requireOffset() }.getOrDefault(windowHeight.toFloat())
                    ((windowHeight - top) / sheetHeight).coerceIn(0f, 1f)
                }
            }
        }
    }
    ModalBottomSheet(
        modifier = if (sheetBounds == null) Modifier else Modifier.onGloballyPositioned { sheet ->
            sheetBounds.value = IntSize(sheet.size.height, sheet.findRootCoordinates().size.height)
        },
        onDismissRequest = onDismissRequest,
        sheetState = sheetState, dragHandle = dragHandle,
        shape = shape,
        containerColor = colors.surfaceContainerHigh.copy(alpha =
            LocalFolioBlur.current.opacity(if (translucent) .88f else .97f, if (translucent) .68f else .85f)),
        contentColor = colors.onSurface, scrimColor = colors.scrim.copy(alpha = if (translucent) .30f else .42f),
    ) {
        FolioWindowBlur(blurRadius = blurRadius, progress = blurProgress)
        content()
    }
}

@Composable
internal fun FolioWindowBlur(confirmation: Boolean = false, blurRadius: Int = if (confirmation) 24 else 20,
    progress: State<Float>? = null) {
    if (Build.VERSION.SDK_INT < 31) return
    val view = LocalView.current
    val window = generateSequence(view.parent) { it.parent }
        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window ?: return
    val blur = LocalFolioBlur.current
    val radius = with(LocalDensity.current) { (blurRadius.dp * blur.multiplier).roundToPx() }
    fun applyRadius(targetRadius: Int) {
        val attributes = window.attributes
        val nextFlags = if (targetRadius > 0) attributes.flags or FLAG_BLUR_BEHIND else attributes.flags and FLAG_BLUR_BEHIND.inv()
        if (attributes.flags != nextFlags || attributes.blurBehindRadius != targetRadius) {
            window.attributes = window.attributes.apply {
                flags = nextFlags
                blurBehindRadius = targetRadius
                if (confirmation && flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) dimAmount = .30f
            }
        }
    }
    if (progress != null && blur.enabled) {
        LaunchedEffect(window, radius, progress) {
            snapshotFlow { (radius * progress.value).roundToInt() }.collect(::applyRadius)
        }
    } else {
        SideEffect { applyRadius(if (blur.enabled) radius else 0) }
    }
}

@Composable
private fun folioWindowBlurEnabled(): Boolean =
    if (Build.VERSION.SDK_INT >= 31 && LocalFolioBlur.current.enabled) observedWindowBlur() else false

@RequiresApi(31)
@Composable
private fun observedWindowBlur(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(WindowManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager.isCrossWindowBlurEnabled) }
    DisposableEffect(manager) {
        val listener = java.util.function.Consumer<Boolean> { enabled = it }
        manager.addCrossWindowBlurEnabledListener(context.mainExecutor, listener)
        onDispose { manager.removeCrossWindowBlurEnabledListener(listener) }
    }
    return enabled
}

@Composable
internal fun FolioDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null
) {
    val shape = MaterialTheme.shapes.extraLarge
    val translucent = folioWindowBlurEnabled()
    AlertDialog(
        onDismissRequest = onDismissRequest, confirmButton = { FolioWindowBlur(confirmation = true); confirmButton() },
        dismissButton = dismissButton, title = title, text = text,
        modifier = Modifier.border(1.dp, folioGlassRim(), shape),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha =
            LocalFolioBlur.current.opacity(if (translucent) .82f else .97f, if (translucent) .68f else .85f)),
        shape = shape
    )
}

@Composable
internal fun FolioPanel(modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (selected) colors.secondaryContainer else colors.surfaceContainerLow, tween(160), label = "Panel fill"
    )
    val rim by animateColorAsState(
        if (selected) colors.primary.copy(alpha = .60f) else colors.outlineVariant.copy(alpha = .65f),
        tween(160), label = "Panel outline"
    )
    Surface(
        modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = fill, border = BorderStroke(1.dp, rim),
        content = content
    )
}

@Composable
internal fun MenuSeparator() {
    HorizontalDivider(Modifier.padding(horizontal=16.dp), thickness=.5.dp, color=MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun FolioOverflowButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: DockBackdrop? = null
) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        FolioGlassSurface(Modifier.size(32.dp), backdrop, shape = CircleShape, raised = false) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.MoreVert, label, Modifier.size(16.dp))
            }
        }
    }
}

/** Shared contextual menu surface; action callbacks remain owned by each screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioOverflowMenu(expanded:Boolean,dismiss:()->Unit,title:String,content:@Composable ColumnScope.()->Unit) {
    if(!expanded) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val onDismiss by rememberUpdatedState(dismiss)
    FolioSheet(onDismissRequest=onDismiss,dragHandle=null,sheetState=sheetState,blurRadius=12,animateBlur=true) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            FilledTonalIconButton(onClick={
                scope.launch {
                    sheetState.hide()
                    if (!sheetState.isVisible) onDismiss()
                }
            }) {Icon(Icons.Outlined.Close,"Close menu")}
        }
        MenuSeparator()
        Column(Modifier.fillMaxWidth().weight(1f,false).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.12f),border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(content=content)
            }
        }
    }
}

@Composable
internal fun FolioMenuItem(label:String,onClick:()->Unit,enabled:Boolean=true) {
    val destructive=label.startsWith("Delete") || label.startsWith("Discard")
    val icon=when {
        destructive -> Icons.Outlined.Delete
        label.contains("Print") -> Icons.Outlined.Print
        label.contains("Share") -> Icons.Outlined.Share
        label.contains("Rename") -> Icons.Outlined.DriveFileRenameOutline
        label.contains("Duplicate") -> Icons.Outlined.ContentCopy
        label.contains("Rotate") -> Icons.Outlined.Rotate90DegreesCw
        label.contains("Text") || label.contains("OCR") -> Icons.Outlined.TextSnippet
        label.contains("Word") -> Icons.Outlined.Description
        label.contains("Favorite",true) -> Icons.Outlined.StarOutline
        label.contains("folder",true) -> Icons.Outlined.FolderOpen
        label == "Reset to original page order" -> Icons.Outlined.Reorder
        label == "Reset to original page sizes" -> Icons.Outlined.AspectRatio
        label.contains("page",true) || label.contains("gallery",true) -> Icons.Outlined.ViewList
        label.contains("filter",true) -> Icons.Outlined.Tune
        label.contains("Export") || label.contains("PDF") -> Icons.Outlined.PictureAsPdf
        label.contains("Edit") || label.contains("retake") -> Icons.Outlined.Edit
        else -> Icons.Outlined.ChevronRight
    }
    DropdownMenuItem(text={Text(label,style=MaterialTheme.typography.bodyLarge)},leadingIcon={Icon(icon,null,Modifier.size(24.dp))},onClick=onClick,enabled=enabled,
        modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),colors=MenuDefaults.itemColors(textColor=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,leadingIconColor=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant))
}

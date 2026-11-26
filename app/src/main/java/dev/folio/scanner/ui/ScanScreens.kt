@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.scanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun ScannerScreen(documentId: String, model: LibraryViewModel, back: () -> Unit, review: (String) -> Unit, replacePageId: String? = null, utilityCapture: (suspend (File) -> Unit)? = null) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val busy by model.busy.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var front by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var auto by rememberSaveable { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var stable by remember { mutableStateOf(false) }
    var corners by remember { mutableStateOf<List<Corner>?>(null) }
    var aspect by remember { mutableFloatStateOf(.75f) }
    var detectionDebug by rememberSaveable { mutableStateOf(false) }
    var focusPoint by remember(front) { mutableStateOf<Offset?>(null) }
    var focusRequest by remember { mutableIntStateOf(0) }
    val reticle = remember { Animatable(0f) }
    LaunchedEffect(front, focusRequest) {
        if (focusPoint != null) {
            reticle.snapTo(.28f)
            reticle.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
            kotlinx.coroutines.delay(390)
            reticle.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
            focusPoint = null
        } else reticle.snapTo(0f)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val preview = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    val scanner = remember { CameraScanner(context, model.pipeline) }
    val attached = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    fun openReview(id: String) {
        if (attached.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) review(id)
    }
    fun failure(message: String) { model.error.value = message; capturing = false }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it; requested = true }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) model.run {
            require(replacePageId == null || uris.size == 1) { "Select one photo to replace a page." }
            if(utilityCapture!=null) { require(uris.size==1) { "Choose one replacement image." }; val temporary=File.createTempFile("pdf-scan-",".jpg",context.cacheDir); try { context.contentResolver.openInputStream(uris.single())!!.use { input -> temporary.outputStream().use { input.copyTo(it) } }; utilityCapture(temporary) } finally { temporary.delete() }; return@run }
            var first: String? = null
            for (uri in uris) {
                context.contentResolver.openInputStream(uri)?.use { input -> val id = model.repository.stageScan(documentId, input, replacement = replacePageId); if (first == null) first = id }
                    ?: error("Could not read this photo. Select it again.")
            }
            first?.let(::openReview)
        }
    }
    DisposableEffect(scanner) { onDispose { attached.set(false); scanner.close() } }
    LaunchedEffect(permitted, front) {
        corners = null; stable = false
        if (permitted) scanner.bind(preview, owner, front, { points, ratio, still -> corners = points; aspect = ratio; stable = still }, ::failure)
    }
    fun capture() {
        if (capturing || busy) return
        capturing = true
        val captureId = UUID.randomUUID().toString()
        val file = if(utilityCapture!=null) File(context.cacheDir,"pdf-scan-$captureId.writing") else File(context.filesDir, "pending-captures/${documentId}_$captureId.writing").apply { parentFile!!.mkdirs() }
        scanner.capture(file, preview.display?.rotation ?: 0, { written ->
            val saved = File(written.parentFile, "${documentId}_$captureId.jpg")
            if (!written.renameTo(saved)) { failure("Could not save capture. Check available storage."); return@capture }
            model.run {
            if(utilityCapture!=null) { try { utilityCapture(saved) } finally { saved.delete(); capturing=false }; return@run }
            var accepted = false
            try { saved.inputStream().use {
                val id = model.repository.stageScan(documentId, it, captureId, replacePageId)
                openReview(id)
                accepted = true
            }; saved.delete() }
            finally { if (!accepted) capturing = false }
        } }, ::failure)
    }
    LaunchedEffect(stable, auto, capturing, busy) { if (stable && auto && !capturing && !busy && !front) capture() }
    val scannerColors=MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        if (permitted) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Live camera preview"
                if (focusPoint != null) stateDescription = "Focus requested"
            })
            if (!front) Canvas(Modifier.fillMaxSize()) {
                corners?.let { points ->
                    val rect = fit(size.width,size.height,aspect)
                    val path = Path().apply { points.forEachIndexed { i,p -> val x=rect[0]+p.x.toFloat()*rect[2]; val y=rect[1]+p.y.toFloat()*rect[3]; if(i==0) moveTo(x,y) else lineTo(x,y) }; close() }
                    drawPath(path,Color.Black.copy(alpha=.8f),style=Stroke(4.dp.toPx()))
                    drawPath(path,scannerColors.primary,style=Stroke(2.dp.toPx()))
                    points.forEach { p -> val at=Offset(rect[0]+p.x.toFloat()*rect[2],rect[1]+p.y.toFloat()*rect[3]); drawCircle(Color.Black,6.dp.toPx(),at); drawCircle(scannerColors.primary,3.dp.toPx(),at) }
                }
            }
            Canvas(Modifier.fillMaxSize().pointerInput(scanner, front, capturing, busy) {
                detectTapGestures { point ->
                    if (!capturing && !busy && scanner.focus(preview, point.x, point.y)) {
                        focusPoint = point
                        focusRequest++
                    }
                }
            }) {
                focusPoint?.let { point ->
                    val strength = reticle.value
                    val radius = 23.dp.toPx() * (1.10f - .10f * strength)
                    val accent = lerp(Color.White, scannerColors.primary, .42f)
                    val corner = point - Offset(radius, radius)
                    val circle = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
                    repeat(4) { index ->
                        val angle = 30f + index * 90f
                        drawArc(Color.Black.copy(alpha = .38f * strength), angle, 30f, false,
                            corner, circle, style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
                        drawArc(accent.copy(alpha = .9f * strength), angle, 30f, false,
                            corner, circle, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                    drawCircle(accent.copy(alpha = .75f * strength), 2.dp.toPx(), point)
                }
            }
        } else Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Camera access",style=MaterialTheme.typography.headlineMedium,color=scannerColors.onSurface)
            Text("Allow camera access to scan. You can also import photos without this permission.",color=scannerColors.onSurface)
            Button(onClick={ permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            if(requested) TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }) { Text("Open app settings") }
        }
        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            ScannerButton(back,Icons.AutoMirrored.Filled.ArrowBack,"Cancel scanning")
            Text("Scan document",color=scannerColors.onSurface,style=MaterialTheme.typography.labelLarge,maxLines=1,modifier=Modifier.weight(1f).background(scannerColors.surfaceContainerHigh.copy(alpha=.94f),androidx.compose.foundation.shape.CircleShape).padding(horizontal=12.dp,vertical=8.dp).then(if(dev.folio.scanner.BuildConfig.DEBUG) Modifier.pointerInput(Unit) { detectTapGestures(onLongPress={ detectionDebug=!detectionDebug }) } else Modifier))
            ScannerButton({ torch=!torch; scanner.torch(torch,::failure) },if(torch) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,"Toggle torch")
            ScannerButton({ front=!front; torch=false },Icons.Outlined.Cameraswitch,"Switch camera")
        }
        @Composable fun captureButton() {
            Surface(onClick={ haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.VirtualKey); capture() },enabled=permitted && !capturing && !busy,shape=androidx.compose.foundation.shape.CircleShape,color=scannerColors.primary,contentColor=scannerColors.onPrimary,border=BorderStroke(4.dp,scannerColors.outlineVariant),modifier=Modifier.size(76.dp).semantics { contentDescription="Capture page" }) {
                Box(Modifier.padding(7.dp).border(2.dp,scannerColors.onPrimary,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) { if(capturing || busy) CircularProgressIndicator(Modifier.size(28.dp),color=scannerColors.onPrimary,strokeWidth=2.dp) }
            }
        }
        @Composable fun gallery() { ScannerButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },Icons.Outlined.PhotoLibrary,"Import photos",!capturing && !busy) }
        @Composable fun autoControl() { FilterChip(auto,{ auto=!auto },label={ Text("Auto") },enabled=!front,colors=FilterChipDefaults.filterChipColors(containerColor=scannerColors.surfaceContainerHigh.copy(alpha=.94f),labelColor=scannerColors.onSurface,selectedContainerColor=scannerColors.primary,selectedLabelColor=scannerColors.onPrimary)) }
        if(landscape) FolioGlassSurface(Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(12.dp), camera = true) {
            Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) { autoControl(); captureButton(); gallery() }
        }
        else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal=16.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=scannerColors.surfaceContainerHigh.copy(alpha=.94f),contentColor=scannerColors.onSurface,shape=androidx.compose.foundation.shape.CircleShape) {
                Text(if(corners==null) "Place document in view" else if(stable) "Ready to capture" else "Hold steady",Modifier.padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,maxLines=1)
            }
            FolioActionTray(camera = true) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) { gallery(); captureButton(); autoControl() }
            }
        }
        if(dev.folio.scanner.BuildConfig.DEBUG && detectionDebug) {
            val diagnostic by produceState(model.pipeline.diagnostics) { while(true) { value=model.pipeline.diagnostics.copy(fps=scanner.fps); kotlinx.coroutines.delay(500) } }
            Surface(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(top=60.dp,start=12.dp),color=scannerColors.surfaceContainerHigh.copy(alpha=.96f),contentColor=scannerColors.onSurface,shape=MaterialTheme.shapes.medium) {
                Text("Detection diagnostics\n${diagnostic.source ?: DetectionSource.MANUAL} \u00b7 ${diagnostic.provider}\nConfidence ${diagnostic.confidence?.let { String.format(java.util.Locale.US,"%.3f",it) } ?: "n/a"}\nInference ${String.format(java.util.Locale.US,"%.1f",diagnostic.inferenceMs)} ms \u00b7 ${String.format(java.util.Locale.US,"%.1f",diagnostic.fps)} FPS",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ScannerButton(click: ()->Unit,icon: androidx.compose.ui.graphics.vector.ImageVector,label: String,enabled: Boolean=true) {
    val colors=MaterialTheme.colorScheme
    FilledIconButton(onClick=click,enabled=enabled,modifier=Modifier.border(1.dp,colors.outlineVariant,androidx.compose.foundation.shape.CircleShape),colors=IconButtonDefaults.filledIconButtonColors(containerColor=colors.surfaceContainerHigh.copy(alpha=.94f),contentColor=colors.onSurface)) { Icon(icon,label) }

}

@Composable
fun CropScreen(pageId: String, model: LibraryViewModel, back: () -> Unit, confirm: () -> Unit, draft: Boolean = false, retake: (() -> Unit)? = null, utilityImage:File?=null, utilityCrop:String="", utilitySave:((List<Corner>)->Unit)?=null) {
    val busy by model.busy.collectAsStateWithLifecycle()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var encoded by rememberSaveable(pageId) { mutableStateOf("") }
    val points = remember(encoded) { decodeCorners(encoded) }
    var framedCrop by rememberSaveable(pageId) { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }
    var cornerIndex by rememberSaveable { mutableIntStateOf(0) }
    var draggingCorner by remember { mutableIntStateOf(-1) }
    var zoom by rememberSaveable(pageId) { mutableStateOf<Float?>(null) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun frame(crop: String) {
        framedCrop = crop
        zoom = null
        pan = Offset.Zero
    }
    LaunchedEffect(pageId) {
        try {
            if (utilityImage != null) {
                bitmap = withContext(Dispatchers.IO) { model.pipeline.decode(utilityImage, 3200) }
                if (encoded.isEmpty()) encoded = utilityCrop.ifBlank { encodeCorners(Geometry.full) }
            } else {
                val scan = if (draft) model.repository.draft(pageId) else null
                val page = if (!draft) requireNotNull(model.repository.dao.page(pageId)) { "Page no longer exists." } else null
                bitmap = withContext(Dispatchers.IO) { model.pipeline.decode(File(scan?.original ?: page!!.originalImageUri), 3200) }
                if (encoded.isEmpty()) encoded = encodeCorners(decodeCorners(scan?.crop ?: page!!.crop))
            }
            if (framedCrop.isEmpty()) frame(encoded)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { android.util.Log.e("Folio crop", "Could not load crop preview",failure); failed = true; model.error.value = "Could not open this page. Return to the document and retry." }
        finally { loading = false }
    }
    Scaffold(topBar = { FolioTopBar(title = { Text(if (draft) "Edit scan" else "Adjust corners") }, navigationIcon = { IconButton(onClick = back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop") } }) }, bottomBar = { FolioActionTray(Modifier.navigationBarsPadding(), horizontalPadding = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            @Composable fun action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                enabled: Boolean = !busy, onClick: () -> Unit) {
                FilledTonalButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = 54.dp), contentPadding = PaddingValues(4.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                    }
                }
            }
            retake?.let { action("Retake", Icons.Outlined.CameraAlt, onClick = it) }
            action("Full image", Icons.Outlined.Fullscreen) { encoded = encodeCorners(Geometry.full); frame(encoded) }
            action("Adjust", Icons.Outlined.Tune) { precise = true }
            action("Detect again", Icons.Outlined.CropFree, enabled = bitmap != null && !busy) { bitmap?.let { image -> model.run {
                val detected = withContext(Dispatchers.Default) { model.pipeline.detect(image) }
                if (detected != null) { encoded = encodeCorners(detected); frame(encoded) }
                else model.error.value = "No document boundary found. Try another background or adjust the corners."
            } } }
        }
        Button(onClick = { model.run { if(utilitySave!=null) utilitySave(points) else { if (draft) model.repository.cropDraft(pageId, points) else model.repository.crop(pageId, points); confirm() } } }, enabled = bitmap != null && !failed && !busy && Geometry.valid(points), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Confirm crop")
        }
    } }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainer)) {
            val image = bitmap
            if (image != null) {
                val currentPoints by rememberUpdatedState(points)
                val framing by rememberUpdatedState(remember(framedCrop) { decodeCorners(framedCrop) })
                val accent = MaterialTheme.colorScheme.primary
                val controlsHeight = minOf(48.dp, maxHeight * .25f)
                val aspect = image.width.toFloat() / image.height
                val imageBitmap = remember(image) { image.asImageBitmap() }
                fun projection(width: Float, height: Float, inset: Float) =
                    cropViewport(width, height, aspect, inset, framing, zoom, pan)
                val density = LocalDensity.current
                fun autoZoom(corners: List<Corner>) = with(density) {
                    cropViewport(maxWidth.toPx(), (maxHeight - controlsHeight).toPx(), aspect, 24.dp.toPx(), corners, null, Offset.Zero).zoom
                }
                val displayedZoom = zoom ?: autoZoom(framing)
                Canvas(Modifier.fillMaxSize().padding(top = controlsHeight).clipToBounds().semantics {
                    contentDescription = "Crop corners"
                    stateDescription = if (draggingCorner >= 0) "Magnifying corner ${draggingCorner + 1}" else "Zoom ${"%.1f".format(java.util.Locale.US, displayedZoom)} times"
                    customActions = listOf(
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Zoom in") { zoom = (displayedZoom * 1.5f).coerceAtMost(6f); true },
                        androidx.compose.ui.semantics.CustomAccessibilityAction("Reset zoom") { zoom = 1f; pan = Offset.Zero; true }
                    )
                }.pointerInput(image) {
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false)
                        fun viewport() = projection(size.width.toFloat(), size.height.toFloat(), 24.dp.toPx())
                        val rect = viewport().bounds
                        var gesturePoints = currentPoints
                        val corners = gesturePoints.map { rect.project(it) }
                        var selected = corners.indices.minBy { (corners[it] - down.position).getDistanceSquared() }
                        if ((corners[selected] - down.position).getDistance() > 48.dp.toPx()) selected = -1
                        draggingCorner=selected
                        try { do {
                            val event=awaitPointerEvent()
                            if(event.changes.count { it.pressed }>1) {
                                selected=-1; draggingCorner=-1
                                zoom = (viewport().zoom * event.calculateZoom()).coerceIn(1f,6f)
                                pan += event.calculatePan()
                                pan = viewport().pan
                            } else {
                                val change=event.changes.first()
                                val amount=change.position-change.previousPosition
                                if(selected>=0) {
                                    val bounds = viewport().bounds
                                    val old = gesturePoints[selected]
                                    val next=gesturePoints.toMutableList().apply { this[selected]=Corner((old.x+amount.x/bounds.width).coerceIn(0.0,1.0),(old.y+amount.y/bounds.height).coerceIn(0.0,1.0)) }
                                    if (Geometry.valid(next)) { gesturePoints = next; encoded = encodeCorners(next) }
                                } else if (viewport().zoom > 1f) {
                                    pan += amount
                                    pan = viewport().pan
                                }
                            }
                            event.changes.forEach { if(it.position!=it.previousPosition) it.consume() }
                        } while(event.changes.any { it.pressed }) } finally { draggingCorner=-1 }
                    }
                }) {
                    val rect = projection(size.width, size.height, 24.dp.toPx()).bounds
                    drawImage(imageBitmap, dstOffset = androidx.compose.ui.unit.IntOffset(rect.left.toInt(), rect.top.toInt()), dstSize = androidx.compose.ui.unit.IntSize(rect.width.toInt().coerceAtLeast(1), rect.height.toInt().coerceAtLeast(1)))
                    val path = Path().apply { points.forEachIndexed { i, p -> val position = rect.project(p); if (i == 0) moveTo(position.x, position.y) else lineTo(position.x, position.y) }; close() }
                    drawPath(path, accent, style = Stroke(3.dp.toPx()))
                    points.forEach { p -> val offset = rect.project(p); drawCircle(Color.Black, 12.dp.toPx(), offset); drawCircle(Color.White, 10.dp.toPx(), offset); drawCircle(accent, 7.dp.toPx(), offset) }
                }
                val zoomed = displayedZoom > 1f
                if (controlsHeight >= 44.dp && (zoomed || draggingCorner < 0 && autoZoom(points) > 1f)) {
                    FilledTonalButton(onClick = { if (zoomed) { zoom = 1f; pan = Offset.Zero } else frame(encoded) },
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp)) {
                        Text(if (zoomed) "Fit image" else "Fit crop")
                    }
                }
                points.getOrNull(draggingCorner)?.let { corner ->
                    Canvas(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp)
                        .size(minOf(88.dp, maxHeight * .25f, maxWidth * .4f)).semantics { contentDescription = "Corner magnifier" }) {
                        val radius = (size.minDimension / 2 - 3.dp.toPx()).coerceAtLeast(0f)
                        if (radius == 0f) return@Canvas
                        val center = this.center
                        val lens=Path().apply { addOval(androidx.compose.ui.geometry.Rect(center-Offset(radius,radius),center+Offset(radius,radius))) }
                        clipPath(lens) {
                            drawCircle(Color.White,radius,center)
                            val scale = radius * 14f / minOf(image.width, image.height)
                            drawImage(imageBitmap,dstOffset=androidx.compose.ui.unit.IntOffset((center.x-corner.x.toFloat()*image.width*scale).toInt(),(center.y-corner.y.toFloat()*image.height*scale).toInt()),dstSize=androidx.compose.ui.unit.IntSize((image.width*scale).toInt().coerceAtLeast(1),(image.height*scale).toInt().coerceAtLeast(1)))
                            drawLine(Color.Black.copy(alpha=.7f),center-Offset(16.dp.toPx(),0f),center+Offset(16.dp.toPx(),0f),3.dp.toPx())
                            drawLine(Color.Black.copy(alpha=.7f),center-Offset(0f,16.dp.toPx()),center+Offset(0f,16.dp.toPx()),3.dp.toPx())
                            drawLine(Color.White,center-Offset(16.dp.toPx(),0f),center+Offset(16.dp.toPx(),0f),1.dp.toPx())
                            drawLine(Color.White,center-Offset(0f,16.dp.toPx()),center+Offset(0f,16.dp.toPx()),1.dp.toPx())
                            drawCircle(accent,3.dp.toPx(),center)
                        }
                        drawCircle(Color.Black.copy(alpha=.35f),radius+2.dp.toPx(),center,style=Stroke(4.dp.toPx()))
                        drawCircle(accent,radius,center,style=Stroke(2.dp.toPx()))
                    }
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (loading) CircularProgressIndicator() else Text("Page unavailable") }
        }
    }
    if (precise) FolioSheet(onDismissRequest = { precise = false }) {
        val labels = listOf("Top left", "Top right", "Bottom right", "Bottom left")
        Column(Modifier.heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Precise corners", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { index, label -> FilterChip(cornerIndex == index, { cornerIndex = index }, label = { Text(label) }) }
            }
            fun move(x: Double? = null, y: Double? = null) {
                val point = points[cornerIndex]
                val next = points.toMutableList().apply { this[cornerIndex] = Corner(x ?: point.x, y ?: point.y) }
                if (Geometry.valid(next)) encoded = encodeCorners(next)
            }
            val original = Geometry.full[cornerIndex]
            FolioRulerDial("Horizontal position", points[cornerIndex].x.toFloat(), 0f..1f, original.x.toFloat(),
                change = { move(x = it.toDouble()) }, accessibilityLabel = "${labels[cornerIndex]} horizontal position")
            FolioRulerDial("Vertical position", points[cornerIndex].y.toFloat(), 0f..1f, original.y.toFloat(),
                change = { move(y = it.toDouble()) }, accessibilityLabel = "${labels[cornerIndex]} vertical position")
            Button(onClick = { precise = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

private data class CropViewport(val bounds: Rect, val zoom: Float, val pan: Offset)

private fun Rect.project(point: Corner) =
    Offset(left + point.x.toFloat() * width, top + point.y.toFloat() * height)

private fun cropViewport(width: Float, height: Float, aspect: Float, inset: Float, points: List<Corner>, zoom: Float?, pan: Offset): CropViewport {
    val usableWidth = (width - inset * 2).coerceAtLeast(1f)
    val usableHeight = (height - inset * 2).coerceAtLeast(1f)
    val fitted = fit(width, height, aspect, inset)
    val fittedWidth = fitted[2]
    val fittedHeight = fitted[3]
    val frame = Geometry.cropFrame(points, usableWidth, usableHeight, fittedWidth, fittedHeight)
    val scale = zoom ?: frame.zoom
    val imageWidth = fittedWidth * scale
    val imageHeight = fittedHeight * scale
    val focus = Offset((.5f - frame.center.x.toFloat()) * imageWidth, (.5f - frame.center.y.toFloat()) * imageHeight)
    val maxX = ((imageWidth - usableWidth) / 2).coerceAtLeast(0f)
    val maxY = ((imageHeight - usableHeight) / 2).coerceAtLeast(0f)
    val shift = Offset((focus.x + pan.x).coerceIn(-maxX, maxX), (focus.y + pan.y).coerceIn(-maxY, maxY))
    val left = (width - imageWidth) / 2 + shift.x
    val top = (height - imageHeight) / 2 + shift.y
    return CropViewport(Rect(left, top, left + imageWidth, top + imageHeight), scale, shift - focus)
}

internal fun fit(width: Float, height: Float, aspect: Float, inset: Float = 0f): FloatArray {
    val w = minOf((width - inset * 2).coerceAtLeast(1f), (height - inset * 2).coerceAtLeast(1f) * aspect); val h = w / aspect
    return floatArrayOf((width - w) / 2, (height - h) / 2, w, h)
}

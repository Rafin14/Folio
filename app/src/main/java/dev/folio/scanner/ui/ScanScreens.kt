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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.platform.LocalContext
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
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val landscape = maxWidth > maxHeight
        if (permitted) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Live camera preview" })
            if (!front) Canvas(Modifier.fillMaxSize()) {
                corners?.let { points ->
                    val rect = fit(size.width,size.height,aspect)
                    val path = Path().apply { points.forEachIndexed { i,p -> val x=rect[0]+p.x.toFloat()*rect[2]; val y=rect[1]+p.y.toFloat()*rect[3]; if(i==0) moveTo(x,y) else lineTo(x,y) }; close() }
                    drawPath(path,Color.Black.copy(alpha=.8f),style=Stroke(4.dp.toPx()))
                    drawPath(path,scannerColors.primary,style=Stroke(2.dp.toPx()))
                    points.forEach { p -> val at=Offset(rect[0]+p.x.toFloat()*rect[2],rect[1]+p.y.toFloat()*rect[3]); drawCircle(Color.Black,6.dp.toPx(),at); drawCircle(scannerColors.primary,3.dp.toPx(),at) }
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
            Surface(onClick=::capture,enabled=permitted && !capturing && !busy,shape=androidx.compose.foundation.shape.CircleShape,color=scannerColors.primary,contentColor=scannerColors.onPrimary,border=BorderStroke(4.dp,scannerColors.outlineVariant),modifier=Modifier.size(76.dp).semantics { contentDescription="Capture page" }) {
                Box(Modifier.padding(7.dp).border(2.dp,scannerColors.onPrimary,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) { if(capturing || busy) CircularProgressIndicator(Modifier.size(28.dp),color=scannerColors.onPrimary,strokeWidth=2.dp) }
            }
        }
        @Composable fun gallery() { ScannerButton({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },Icons.Outlined.PhotoLibrary,"Import photos",!capturing && !busy) }
        @Composable fun autoControl() { FilterChip(auto,{ auto=!auto },label={ Text("Auto") },enabled=!front,colors=FilterChipDefaults.filterChipColors(containerColor=scannerColors.surfaceContainerHigh.copy(alpha=.94f),labelColor=scannerColors.onSurface,selectedContainerColor=scannerColors.primary,selectedLabelColor=scannerColors.onPrimary)) }
        if(landscape) Column(Modifier.align(Alignment.CenterEnd).safeDrawingPadding().padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) { autoControl(); captureButton(); gallery() }
        else Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(horizontal=16.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Surface(color=scannerColors.surfaceContainerHigh.copy(alpha=.94f),contentColor=scannerColors.onSurface,shape=androidx.compose.foundation.shape.CircleShape) {
                Text(if(corners==null) "Place document in view" else if(stable) "Ready to capture" else "Hold steady",Modifier.padding(horizontal=14.dp,vertical=6.dp),style=MaterialTheme.typography.labelMedium,maxLines=1)
            }
            Row(Modifier.fillMaxWidth().widthIn(max=480.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) { gallery(); captureButton(); autoControl() }
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
    val points = decodeCorners(encoded)
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var precise by rememberSaveable { mutableStateOf(false) }
    var cornerIndex by rememberSaveable { mutableIntStateOf(0) }
    var draggingCorner by remember { mutableIntStateOf(-1) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pageId) {
        try {
            if(utilityImage!=null) { bitmap=withContext(Dispatchers.IO) { model.pipeline.decode(utilityImage,3200) }; if(encoded.isEmpty()) encoded=utilityCrop.ifBlank { encodeCorners(Geometry.full) }; return@LaunchedEffect }
            val scan = if (draft) model.repository.draft(pageId) else null
            val page = if (!draft) requireNotNull(model.repository.dao.page(pageId)) { "Page no longer exists." } else null
            val decoded = withContext(Dispatchers.IO) { model.pipeline.decode(File(scan?.original ?: page!!.originalImageUri), 3200) }
            bitmap = decoded; if (encoded.isEmpty()) encoded = encodeCorners(decodeCorners(scan?.crop ?: page!!.crop))
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { android.util.Log.e("Folio crop", "Could not load crop preview",failure); failed = true; model.error.value = "Could not open this page. Return to the document and retry." }
        finally { loading = false }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(if (draft) "Edit scan" else "Adjust corners") }, navigationIcon = { IconButton(onClick = back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop") } }) }, bottomBar = { Column(Modifier.navigationBarsPadding().padding(horizontal=12.dp,vertical=4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            retake?.let { TextButton(onClick = it, enabled = !busy) { Text("Retake") } }
            TextButton(onClick = { encoded = encodeCorners(Geometry.full) }, enabled = !busy) { Text("Full image") }
            TextButton(onClick = { precise = true }, enabled = !busy) { Text("Adjust") }
            TextButton(onClick = { bitmap?.let { image -> model.run {
                val detected = withContext(Dispatchers.Default) { model.pipeline.detect(image) }
                if (detected != null) encoded = encodeCorners(detected) else model.error.value = "No document boundary found. Try another background or adjust the corners."
            } } }, enabled = bitmap != null && !busy) { Text("Detect again") }
        }
        Button(onClick = { model.run { if(utilitySave!=null) utilitySave(points) else { if (draft) model.repository.cropDraft(pageId, points) else model.repository.crop(pageId, points); confirm() } } }, enabled = bitmap != null && !failed && !busy && Geometry.valid(points), modifier = Modifier.fillMaxWidth()) { Text("Confirm crop") }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val image = bitmap
            if (image != null) {
                val currentPoints by rememberUpdatedState(points)
                val accent = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize().clipToBounds().semantics { contentDescription = "Crop corners"; stateDescription=if(draggingCorner>=0) "Magnifying corner ${draggingCorner+1}" else "Zoom ${zoom} times"; customActions=listOf(androidx.compose.ui.semantics.CustomAccessibilityAction("Zoom in") { zoom=(zoom*1.5f).coerceAtMost(6f); true },androidx.compose.ui.semantics.CustomAccessibilityAction("Reset zoom") { zoom=1f; pan=Offset.Zero; true }) }.background(MaterialTheme.colorScheme.surfaceContainer).pointerInput(image) {
                    awaitEachGesture {
                        val down=awaitFirstDown(requireUnconsumed=false)
                        fun viewport()=fit(size.width.toFloat(),size.height.toFloat(),image.width.toFloat()/image.height,24.dp.toPx()).also { r ->
                            r[0]=size.width/2+(r[0]-size.width/2)*zoom+pan.x; r[1]=size.height/2+(r[1]-size.height/2)*zoom+pan.y; r[2]*=zoom; r[3]*=zoom
                        }
                        val rect=viewport()
                        var selected=currentPoints.indices.minBy { i -> val p=currentPoints[i]; (Offset(rect[0]+p.x.toFloat()*rect[2],rect[1]+p.y.toFloat()*rect[3])-down.position).getDistance() }
                        val p=currentPoints[selected]
                        if((Offset(rect[0]+p.x.toFloat()*rect[2],rect[1]+p.y.toFloat()*rect[3])-down.position).getDistance()>48.dp.toPx()) selected=-1
                        draggingCorner=selected
                        try { do {
                            val event=awaitPointerEvent()
                            if(event.changes.count { it.pressed }>1) {
                                selected=-1; draggingCorner=-1; zoom=(zoom*event.calculateZoom()).coerceIn(1f,6f); pan+=event.calculatePan()
                            } else {
                                val change=event.changes.first()
                                val amount=change.position-change.previousPosition
                                if(selected>=0) {
                                    val r=viewport(); val old=currentPoints[selected]
                                    val next=currentPoints.toMutableList().apply { this[selected]=Corner((old.x+amount.x/r[2]).coerceIn(0.0,1.0),(old.y+amount.y/r[3]).coerceIn(0.0,1.0)) }
                                    if(Geometry.valid(next)) encoded=encodeCorners(next)
                                } else if(zoom>1f) pan+=amount
                            }
                            val fitted=fit(size.width.toFloat(),size.height.toFloat(),image.width.toFloat()/image.height,24.dp.toPx())
                            val maxX=(fitted[2]*zoom-size.width).coerceAtLeast(0f)/2; val maxY=(fitted[3]*zoom-size.height).coerceAtLeast(0f)/2
                            pan=Offset(pan.x.coerceIn(-maxX,maxX),pan.y.coerceIn(-maxY,maxY))
                            event.changes.forEach { if(it.position!=it.previousPosition) it.consume() }
                        } while(event.changes.any { it.pressed }) } finally { draggingCorner=-1 }
                    }
                }) {
                    val rect = fit(size.width, size.height, image.width.toFloat() / image.height, 24.dp.toPx()).also { r -> r[0]=size.width/2+(r[0]-size.width/2)*zoom+pan.x; r[1]=size.height/2+(r[1]-size.height/2)*zoom+pan.y; r[2]*=zoom; r[3]*=zoom }
                    drawImage(image.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(rect[0].toInt(), rect[1].toInt()), dstSize = androidx.compose.ui.unit.IntSize(rect[2].toInt(), rect[3].toInt()))
                    val path = Path().apply { points.forEachIndexed { i, p -> val x = rect[0] + p.x.toFloat() * rect[2]; val y = rect[1] + p.y.toFloat() * rect[3]; if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }
                    drawPath(path, accent, style = Stroke(3.dp.toPx()))
                    points.forEach { p -> val offset = Offset(rect[0] + p.x.toFloat() * rect[2], rect[1] + p.y.toFloat() * rect[3]); drawCircle(Color.Black, 12.dp.toPx(), offset); drawCircle(Color.White, 10.dp.toPx(), offset); drawCircle(accent, 7.dp.toPx(), offset) }
                    if(draggingCorner in points.indices) {
                        val corner=points[draggingCorner]
                        val active=Offset(rect[0]+corner.x.toFloat()*rect[2],rect[1]+corner.y.toFloat()*rect[3])
                        val radius=minOf(56.dp.toPx(),size.minDimension/5)
                        val gap=16.dp.toPx()
                        val center=Offset(if(active.x<size.width/2) size.width-radius-gap else radius+gap,if(active.y<size.height/2) size.height-radius-gap else radius+gap)
                        val lens=Path().apply { addOval(androidx.compose.ui.geometry.Rect(center-Offset(radius,radius),center+Offset(radius,radius))) }
                        clipPath(lens) {
                            drawCircle(Color.White,radius,center)
                            val scale=maxOf(rect[2]/image.width,rect[3]/image.height)*2.5f
                            drawImage(image.asImageBitmap(),dstOffset=androidx.compose.ui.unit.IntOffset((center.x-corner.x.toFloat()*image.width*scale).toInt(),(center.y-corner.y.toFloat()*image.height*scale).toInt()),dstSize=androidx.compose.ui.unit.IntSize((image.width*scale).toInt(),(image.height*scale).toInt()))
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
    if (precise) ModalBottomSheet(onDismissRequest = { precise = false }) {
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
            Text("Horizontal position")
            Slider(points[cornerIndex].x.toFloat(), { move(x = it.toDouble()) }, modifier = Modifier.semantics { contentDescription = "${labels[cornerIndex]} horizontal position" })
            Text("Vertical position")
            Slider(points[cornerIndex].y.toFloat(), { move(y = it.toDouble()) }, modifier = Modifier.semantics { contentDescription = "${labels[cornerIndex]} vertical position" })
            Button(onClick = { precise = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

internal fun fit(width: Float, height: Float, aspect: Float, inset: Float = 0f): FloatArray {
    val w = minOf((width - inset * 2).coerceAtLeast(1f), (height - inset * 2).coerceAtLeast(1f) * aspect); val h = w / aspect
    return floatArrayOf((width - w) / 2, (height - h) / 2, w, h)
}

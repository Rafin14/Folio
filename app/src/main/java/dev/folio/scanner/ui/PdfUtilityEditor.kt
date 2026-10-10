@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.pdf.*
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

@Composable
internal fun PdfUtilityEditor(id:String,initialPages:List<UtilityPage>,model:LibraryViewModel,back:()->Unit,export:()->Unit,changed:()->Unit,initialPage:Int=0,showGallery:(()->Unit)?=null,pageSelected:((Int)->Unit)?=null) {
    val utility=model.utility
    var pages by remember(id) { mutableStateOf(initialPages) }
    val pager=rememberPagerState(initialPage=initialPage.coerceIn(0,pages.lastIndex),pageCount={ pages.size })
    LaunchedEffect(pager.currentPage) {pageSelected?.invoke(pager.currentPage)}
    val scope=rememberCoroutineScope()
    val busy by model.busy.collectAsStateWithLifecycle()
    val colors=MaterialTheme.colorScheme
    val palette=listOf(colors.onSurface,colors.primary,colors.tertiary,colors.error,colors.secondary)
    var inkColor by remember { mutableStateOf(palette[1].toArgb()) }
    var pen by rememberSaveable { mutableFloatStateOf(.004f) }
    var drawing by rememberSaveable { mutableStateOf(false) }
    var zoomedPage by remember {mutableStateOf<String?>(null)}
    var operationMenu by remember {mutableStateOf(false)}
    var organize by rememberSaveable { mutableStateOf(false) }
    var replace by rememberSaveable { mutableStateOf(false) }
    var choosePage by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    var scan by rememberSaveable { mutableStateOf(false) }
    var replacementTarget by rememberSaveable { mutableStateOf("") }
    var replacementFile by rememberSaveable { mutableStateOf("") }
    fun update(page:UtilityPage) { pages=pages.map { if(it.id==page.id) page else it }; utility.updatePages(id,pages); changed() }
    var replacementPdf by rememberSaveable { mutableStateOf("") }
    var naturalWidth by rememberSaveable { mutableDoubleStateOf(0.0) }
    var naturalHeight by rememberSaveable { mutableDoubleStateOf(0.0) }
    var targetWidth by rememberSaveable { mutableDoubleStateOf(210.0) }
    var targetHeight by rememberSaveable { mutableDoubleStateOf(297.0) }
    fun stage(file:File,natural:PageLayout?=null) { naturalWidth=natural?.widthMm ?: 0.0; naturalHeight=natural?.heightMm ?: 0.0; replacementFile=file.path; replacementPdf=""; scan=false; replace=false }
    suspend fun copyReplacement(file:File) { val copy=File(utility.folder(id),"replace-${UUID.randomUUID()}.jpg"); withContext(Dispatchers.IO) { file.copyTo(copy) }; stage(copy) }
    val context=LocalContext.current
    val devicePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.run {
        val file=File(utility.folder(id),"replace-${UUID.randomUUID()}.input"); utility.copy(uri,file)
        val pdf=withContext(Dispatchers.IO) { file.inputStream().use { input -> val header=ByteArray(1024); val n=input.read(header); n>0 && String(header,0,n,Charsets.ISO_8859_1).contains("%PDF-") } }
        if(pdf || context.contentResolver.getType(uri)=="application/pdf") replacementPdf=file.path else stage(file)
    } }
    val current=pages.getOrNull(pager.currentPage.coerceAtMost(pages.lastIndex)) ?: return
    BackHandler(enabled=scan || replacementFile.isNotEmpty() || replacementPdf.isNotEmpty()) { if(scan) scan=false else if(replacementPdf.isNotEmpty()) replacementPdf="" else replacementFile="" }
    if(replacementPdf.isNotEmpty()) { PdfReplacementPagePicker(File(replacementPdf),model,{ replacementPdf="" }) { image,layout -> stage(image,layout) }; return }
    if(scan) { ScannerScreen(id,model,{ scan=false },{},utilityCapture={ copyReplacement(it) }); return }
    if(replacementFile.isNotEmpty()) { PdfReplacementEditor(File(replacementFile),model,{ replacementFile="" },PageLayout("Custom","Fit",targetWidth,targetHeight),if(naturalWidth>0) PageLayout("Custom","Fit",naturalWidth,naturalHeight) else null) { result,layout ->
        model.run {
            val page=requireNotNull(pages.firstOrNull { it.id==replacementTarget }) { "The replacement target is no longer available. Cancel and choose the page again." }
            val pdf=File(utility.folder(id),"replacement-${UUID.randomUUID()}.pdf")
            withContext(Dispatchers.IO) { utility.engine.generate(listOf(result),pdf,"Replacement page",layouts=listOf(layout)) }
            update(page.copy(replacement=pdf.path,rotation=0,ink=emptyList(),notes=emptyList()))
            replacementFile=""
        }
    }; return }
    fun leave() { if(drawing) drawing=false else back() }
    BackHandler { leave() }
    Scaffold(topBar={ TopAppBar(title={ Text("${pager.currentPage+1} of ${pages.size}",maxLines=1) },navigationIcon={ FilledTonalButton(onClick=export,enabled=!busy,modifier=Modifier.padding(start=8.dp)) { Text("Export") } },actions={ Box {
        IconButton(onClick={operationMenu=true}) {Icon(Icons.Outlined.MoreVert,"PDF editor menu")}
        FolioOverflowMenu(operationMenu,{operationMenu=false},"Edit PDF") {
            if(showGallery!=null) FolioMenuItem(label="Page gallery",onClick={operationMenu=false;showGallery()})
            FolioMenuItem(label="Save and Export",enabled=!busy,onClick={operationMenu=false;export()})
            MenuSeparator()
            FolioMenuItem(label="Arrange PDF pages",onClick={operationMenu=false;organize=true})
        }
    }; IconButton(onClick={ organize=true }) { Icon(Icons.Outlined.ViewList,"Arrange PDF pages") }; IconButton(onClick=::leave,enabled=!busy) { Icon(Icons.Outlined.Close,"Close PDF editor") } }) },bottomBar={
        Column(Modifier.navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(drawing,{ drawing=!drawing },label={ Text(if(drawing) "Drawing" else "Draw") })
                OutlinedButton(onClick={ update(current.rotated()) }) { Icon(Icons.Outlined.Rotate90DegreesCw,null); Text("Rotate") }
                OutlinedButton(onClick={ model.run { val layout=utility.replacementTargetLayout(id,current); targetWidth=layout.widthMm; targetHeight=layout.heightMm; replacementTarget=current.id; replace=true } },enabled=!busy) { Text("Replace") }
                OutlinedButton(colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={ remove=true },enabled=pages.size>1) { Text("Delete") }
                OutlinedButton(onClick={ update(current.copy(ink=current.ink.dropLast(1))) },enabled=current.ink.isNotEmpty()) { Text("Undo drawing") }
            }
            if(drawing) Row(Modifier.horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                palette.forEachIndexed { i,color -> Surface(onClick={ inkColor=color.toArgb() },shape=MaterialTheme.shapes.small,color=color,border=BorderStroke(if(inkColor==color.toArgb()) 2.dp else 1.dp,colors.outline),modifier=Modifier.size(48.dp).padding(2.dp).semantics { contentDescription="Drawing color ${i+1}" }) {} }
                listOf(.002f,.004f,.009f).forEachIndexed { i,w -> FilterChip(pen==w,{ pen=w },label={ Text(listOf("Fine","Medium","Bold")[i]) }) }
            }
            Text(if(drawing) "Draw on the page. Turn Draw off to swipe." else "Swipe between pages. Export saves a new file.",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
        }
    }) { padding -> HorizontalPager(pager,Modifier.fillMaxSize().padding(padding),userScrollEnabled=!drawing && !busy && zoomedPage!=current.id,key={ pages[it].id }) { index ->
        val page=pages[index]
        var image by remember(page.id,page.replacement) { mutableStateOf<Bitmap?>(null) }
        var error by remember { mutableStateOf("") }
        var displayedRotation by remember(page.id) { mutableFloatStateOf(page.rotation.toFloat()) }
        var rotationTarget by remember(page.id) { mutableFloatStateOf(page.rotation.toFloat()) }
        LaunchedEffect(page.rotation) { if((rotationTarget.toInt()%360)!=page.rotation) rotationTarget+=90f }
        val angle by animateFloatAsState(rotationTarget,tween(240),label="Page rotation")
        LaunchedEffect(page.id,page.replacement,page.rotation) {
            try { if(image!=null) delay(250); val loaded=utility.preview(id,page); val old=image; image=loaded; displayedRotation=rotationTarget; old?.recycle() } catch(t:CancellationException) { throw t } catch(t:Exception) { error="Could not render this page." }
        }
        DisposableEffect(page.id) { onDispose { image?.recycle() } }
        var stroke by remember(page.id) { mutableStateOf(emptyList<InkPoint>()) }
        val latestPage by rememberUpdatedState(page)
        val bitmap=image
        if(bitmap==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { if(error.isBlank()) CircularProgressIndicator() else Text(error) }
        else {
            val aspect=bitmap.width.toFloat()/bitmap.height
            var zoom by rememberSaveable(page.id) {mutableFloatStateOf(1f)}
            var pan by remember(page.id) {mutableStateOf(Offset.Zero)}
            var canvasSize by remember {mutableStateOf(IntSize.Zero)}
            fun transform(scale:Float,delta:Offset=Offset.Zero) {
                zoom=(zoom*scale).coerceIn(1f,6f)
                val r=fit(canvasSize.width.toFloat(),canvasSize.height.toFloat(),aspect,0f)
                val maxX=(r[2]*zoom-canvasSize.width).coerceAtLeast(0f)/2
                val maxY=(r[3]*zoom-canvasSize.height).coerceAtLeast(0f)/2
                pan=Offset((pan.x+delta.x).coerceIn(-maxX,maxX),(pan.y+delta.y).coerceIn(-maxY,maxY))
                zoomedPage=if(zoom>1f) page.id else null
            }
            val gestures=rememberTransformableState {scale,delta,_ ->transform(scale,delta)}
            Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged {canvasSize=it}.transformable(gestures,canPan={zoom>1f && !drawing})) {
            Canvas(Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y}.semantics {
                contentDescription="PDF page ${index+1} canvas";stateDescription="Zoom ${"%.1f".format(java.util.Locale.US,zoom)} times"
                customActions=listOf(CustomAccessibilityAction("Zoom in") {transform(1.5f);true},CustomAccessibilityAction("Zoom out") {transform(1f/1.5f);true},CustomAccessibilityAction("Reset zoom") {transform(1f/zoom);pan=Offset.Zero;true})
            }.pointerInput(page.id,drawing,bitmap) {
                if(drawing) awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val r=fit(size.width.toFloat(),size.height.toFloat(),aspect,12.dp.toPx())
                    fun point(p:Offset)=InkPoint(((p.x-r[0])/r[2]).coerceIn(0f,1f),((p.y-r[1])/r[3]).coerceIn(0f,1f))
                    stroke=if(down.position.x in r[0]..r[0]+r[2] && down.position.y in r[1]..r[1]+r[3]) listOf(point(down.position)) else emptyList()
                    while(true) {
                        val event=awaitPointerEvent()
                        if(event.changes.count {it.pressed}>1) {stroke=emptyList();break}
                        val change=event.changes.firstOrNull {it.id==down.id} ?: break
                        if(change.isConsumed) {stroke=emptyList();break}
                        if(stroke.isNotEmpty()) {stroke=stroke+point(change.position);change.consume()}
                        if(!change.pressed) {if(stroke.isNotEmpty()) update(latestPage.copy(ink=latestPage.ink+PdfInk(inkColor,pen,stroke)));stroke=emptyList();break}
                    }
                }
            }) {
                val r=fit(size.width,size.height,aspect,12.dp.toPx())
                // Rendering catches up at animation end; rotation is confined to this page.
                val delta=angle-displayedRotation
                rotate(delta,pivot=Offset(size.width/2,size.height/2)) { drawImage(bitmap.asImageBitmap(),dstOffset=IntOffset(r[0].toInt(),r[1].toInt()),dstSize=IntSize(r[2].toInt(),r[3].toInt())) }
                fun paint(ink:PdfInk) { if(ink.points.isEmpty()) return; val path=Path().apply { ink.points.forEachIndexed { i,p -> if(i==0) moveTo(r[0]+p.x*r[2],r[1]+p.y*r[3]) else lineTo(r[0]+p.x*r[2],r[1]+p.y*r[3]) } }; drawPath(path,Color(ink.color),style=Stroke(ink.width*minOf(r[2],r[3]),cap=StrokeCap.Round,join=StrokeJoin.Round)) }
                page.ink.forEach(::paint); if(stroke.isNotEmpty()) paint(PdfInk(inkColor,pen,stroke))
                page.notes.forEach { n -> drawIntoCanvas { canvas -> val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=n.color; textSize=n.size*r[2] }; n.text.lines().forEachIndexed { line,text -> canvas.nativeCanvas.drawText(text,r[0]+n.x*r[2],r[1]+n.y*r[3]+line*paint.textSize*1.3f,paint) } } }
                drawRect(colors.outlineVariant,Offset(r[0],r[1]),androidx.compose.ui.geometry.Size(r[2],r[3]),style=Stroke(1.dp.toPx()))
            }
            if(zoom>1f) FilledTonalIconButton(onClick={transform(1f/zoom);pan=Offset.Zero},modifier=Modifier.align(Alignment.TopEnd).padding(12.dp)) {Icon(Icons.Outlined.ZoomOutMap,"Fit page")}
            }
        }
    } }
    if(remove) AlertDialog(onDismissRequest={ remove=false },title={ Text("Delete this PDF page?") },text={ Text("The page is removed from this edited copy. The source remains unchanged.") },confirmButton={ Button(colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError),onClick={ val next=pages-current; pages=next; utility.updatePages(id,next); changed(); remove=false }) { Text("Delete page") } },dismissButton={ OutlinedButton(onClick={ remove=false }) { Text("Cancel") } })
    if(organize) ModalBottomSheet(onDismissRequest={ organize=false }) { Column(Modifier.heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(16.dp)) { Text("Drag pages to arrange",style=MaterialTheme.typography.titleLarge); ReorderList(pages.map { it.id },{ key -> "Page ${pages.indexOfFirst { it.id==key }+1} · Source ${pages.first { it.id==key }.source}" }) { order -> val activeId=current.id; pages=order.map { key -> pages.first { it.id==key } }; utility.updatePages(id,pages); changed(); scope.launch { pager.scrollToPage(pages.indexOfFirst { it.id==activeId }) } }; Button(onClick={ organize=false },modifier=Modifier.fillMaxWidth()) { Text("Done") } } }
    if(replace) AlertDialog(onDismissRequest={ replace=false },title={ Text("Replace this page") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick={ replace=false; devicePicker.launch(arrayOf("image/*","application/pdf")) },modifier=Modifier.fillMaxWidth()) { Text("Choose device image or PDF") }; OutlinedButton(onClick={ replace=false; scan=true },modifier=Modifier.fillMaxWidth()) { Text("Use Folio scanner") }; OutlinedButton(onClick={ replace=false; choosePage=true },modifier=Modifier.fillMaxWidth()) { Text("Choose Folio page") } } },confirmButton={ TextButton(onClick={ replace=false }) { Text("Cancel") } })
    if(choosePage) FolioPageChooser(model,true,{ choosePage=false }) { choices -> model.run { val choice=choices.single(); val snapshots=model.repository.snapshotImages(choice.documentId,File(utility.folder(id),"folio-${UUID.randomUUID()}"),setOf(choice.pageId)); copyReplacement(snapshots.single()); choosePage=false } }
}


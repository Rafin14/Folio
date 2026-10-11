@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.graphics.Bitmap
import android.content.Intent
import android.content.ClipData
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.pdf.FolioPrintAdapter
import dev.folio.scanner.pdf.printAttributes
import dev.folio.scanner.pdf.pdfFileStem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.scanner.pdf.UtilityPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Browse the existing temporary PDF copy, without creating Room documents or pages. */
@Composable
internal fun PdfPageGallery(id:String,pages:List<UtilityPage>,title:String,managed:Boolean,
    model:LibraryViewModel,selectedPage:Int,back:()->Unit,saveToFolio:(()->Unit)?=null,saving:Boolean=false,reordered:(List<UtilityPage>)->Unit={},openPage:(Int)->Unit) {
    val context=LocalContext.current
    val preferences=remember { context.getSharedPreferences("appearance",0) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("pdfGalleryGrid",true)) }
    var selectedIds by rememberSaveable(id) {mutableStateOf(emptyList<String>())}
    var menu by remember {mutableStateOf(false)}
    var exportIds by rememberSaveable {mutableStateOf(emptyList<String>())}
    var message by remember {mutableStateOf("")}
    val busy by model.busy.collectAsStateWithLifecycle()
    val selection=selectedIds.filter {key ->pages.any {it.id==key}}
    fun toggle(key:String) {selectedIds=if(key in selection) selection-key else selection+key}
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {uri ->if(uri!=null) model.run {
        val file=model.utility.snapshotEdited(id,exportIds.takeIf {it.isNotEmpty()}?.toSet())
        try {withContext(Dispatchers.IO) {model.utility.publishOutput(file,uri)};message="PDF export verified"} finally {file.delete()}
    }}
    fun output(print:Boolean) {val ids=selectedIds.takeIf {it.isNotEmpty()}?.toSet();model.run {
        val file=model.utility.snapshotEdited(id,ids)
        var handedOff=false
        try {
            if(print) context.getSystemService(PrintManager::class.java).print(title,FolioPrintAdapter(file,title),printAttributes(file))
            else {val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file,pdfFileStem(title)+".pdf")
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,uri).apply {clipData=ClipData.newRawUri("Folio PDF",uri)}.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share PDF"))}
            handedOff=true
        } finally {if(!handedOff) file.delete()}
    }}
    val rendering=remember(id) {Mutex()}
    var ordered by remember(id,pages) {mutableStateOf(pages)}
    val bounds=remember(id) {mutableStateMapOf<String,Rect>()}
    var dragging by remember {mutableStateOf<String?>(null)}
    var target by remember {mutableStateOf<String?>(null)}
    var dragOffset by remember {mutableStateOf(Offset.Zero)}
    var dragOrigin by remember {mutableStateOf(Offset.Zero)}
    var dragScroll by remember {mutableFloatStateOf(0f)}
    var gridBounds by remember {mutableStateOf(Rect.Zero)}
    val gridState=rememberLazyGridState()
    val activePage=pages.getOrNull(selectedPage)?.id
    LaunchedEffect(dragging) {
        while(dragging!=null) {
            val y=dragOrigin.y+dragOffset.y
            val edge=gridBounds.height*.12f
            val delta=when {y<gridBounds.top+edge -> -12f;y>gridBounds.bottom-edge -> 12f;else -> 0f}
            if(delta!=0f) dragScroll+=gridState.scrollBy(delta)
            delay(16)
        }
    }
    BackHandler {if(selection.isNotEmpty()) selectedIds=emptyList() else if(!busy && !saving) back()}
    Scaffold(topBar={TopAppBar(title={Column {
        Text(if(selection.isEmpty()) title else "${selection.size} selected",maxLines=1,overflow=TextOverflow.Ellipsis)
        Text("${pages.size} pages · ${if(managed) "Folio PDF" else "Device PDF"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},navigationIcon={IconButton(onClick={if(selection.isNotEmpty()) selectedIds=emptyList() else back()},enabled=!saving && !busy) {Icon(if(selection.isEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Outlined.Close,if(selection.isEmpty()) "Back" else "Clear PDF selection")}},actions={IconButton(onClick={grid=!grid;preferences.edit {putBoolean("pdfGalleryGrid",grid)}},enabled=dragging==null && !busy) {Icon(if(grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView,if(grid) "List view" else "Grid view")};Box {
        IconButton(onClick={menu=true},enabled=!busy) {Icon(Icons.Outlined.MoreVert,"PDF gallery menu")}
        FolioOverflowMenu(menu,{menu=false},"PDF pages") {
            FolioMenuItem(label="Select all pages",onClick={selectedIds=pages.map {it.id};menu=false})
            FolioMenuItem(label="Clear selection",enabled=selection.isNotEmpty(),onClick={selectedIds=emptyList();menu=false})
        }
    };if(selection.isEmpty()) saveToFolio?.let {FilledTonalButton(onClick=it,enabled=!saving && !busy) {Text(if(saving) "Saving…" else "Save to Folio")}}})},bottomBar={Column(Modifier.navigationBarsPadding().padding(horizontal=12.dp,vertical=8.dp)) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick={output(true)},enabled=!busy && !saving) {Icon(Icons.Outlined.Print,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("Print")}
            OutlinedButton(onClick={output(false)},enabled=!busy && !saving) {Icon(Icons.Outlined.Share,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("Share")}
            OutlinedButton(onClick={message="";exportIds=selectedIds;destination.launch(pdfFileStem(title)+if(selectedIds.isEmpty()) ".pdf" else " selected.pdf")},enabled=!busy && !saving) {Icon(Icons.Outlined.SaveAlt,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("Export")}
        }
    }}) {padding ->
        LazyVerticalGrid(if(grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),Modifier.fillMaxSize().padding(padding).onGloballyPositioned {gridBounds=it.boundsInRoot()}.semantics {contentDescription="PDF page gallery"},state=gridState,
            contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            itemsIndexed(ordered,key={_,page -> page.id}) {index,page ->
                DisposableEffect(page.id) {onDispose {bounds.remove(page.id)}}
                var failed by remember(id,page) {mutableStateOf(false)}
                val image by produceState<Bitmap?>(null,id,page) {
                    try {value=rendering.withLock {model.utility.galleryPreview(id,page)}}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(_:Exception) {failed=true}
                }
                val checked=page.id in selection
                val selected=checked || selection.isEmpty() && page.id==activePage
                val caption=if(dragging!=null && target==page.id && dragging!=page.id) "Move to position ${index+1}" else "Page ${index+1}"
                Column(Modifier.animateItem().onGloballyPositioned {bounds[page.id]=it.boundsInRoot()}.zIndex(if(dragging==page.id) 1f else 0f).graphicsLayer {if(dragging==page.id) {translationX=dragOffset.x;translationY=dragOffset.y+dragScroll;scaleX=1.04f;scaleY=1.04f}}.pageDragGesture(page.id,ordered.map {it.id},bounds,{dragging=it;dragScroll=0f;dragOrigin=it?.let {key ->bounds[key]?.center} ?: Offset.Zero},{target=it},{dragOffset=it},{},drop={order ->
                    ordered=order.map {key -> ordered.first {it.id==key}};model.utility.updatePages(id,ordered);reordered(ordered)
                },enabled=selection.isEmpty() && !busy)) {
                    Surface(onClick={if(selection.isEmpty()) openPage(index) else toggle(page.id)},enabled=!busy,shape=MaterialTheme.shapes.medium,
                        color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border=BorderStroke(if(selected || target==page.id) 2.dp else 1.dp,if(selected || target==page.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier=Modifier.semantics {contentDescription="Open PDF page ${index+1}";this.selected=selected;stateDescription=if(failed) "Preview unavailable" else if(image==null) "Loading preview" else "Preview loaded"}) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box((if(grid) Modifier.fillMaxWidth().aspectRatio(.75f) else Modifier.size(96.dp,128.dp)).padding(6.dp),contentAlignment=Alignment.Center) {
                            image?.let {Image(it.asImageBitmap(),"PDF page ${index+1} thumbnail",Modifier.fillMaxSize())}
                                ?: if(failed) Text("Preview unavailable",style=MaterialTheme.typography.bodySmall) else CircularProgressIndicator(Modifier.size(24.dp))
                        }
                        if(!grid) {Checkbox(checked,{toggle(page.id)},enabled=!busy,modifier=Modifier.semantics {contentDescription="Select PDF page ${index+1}"});Text(caption,Modifier.weight(1f).padding(8.dp),style=MaterialTheme.typography.titleMedium);Icon(Icons.Outlined.ChevronRight,null,Modifier.padding(end=12.dp))}
                        }
                    }
                    if(grid) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked,{toggle(page.id)},enabled=!busy,modifier=Modifier.semantics {contentDescription="Select PDF page ${index+1}"});Text(caption,style=MaterialTheme.typography.titleSmall,color=if(target==page.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)}
                }
            }
        }
    }
}

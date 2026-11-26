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
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.scanner.pdf.UtilityPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Browse the existing temporary PDF copy, without creating Room documents or pages. */
@Composable
internal fun PdfPageGallery(id:String,pages:List<UtilityPage>,title:String,managed:Boolean,
    model:LibraryViewModel,selectedPage:Int,back:()->Unit,saveToFolio:(()->Unit)?=null,saving:Boolean=false,reordered:(List<UtilityPage>)->Unit={},searchMatch:Boolean=false,openPage:(Int)->Unit) {
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
    val gridState = rememberLazyGridState()
    val activePage = pages.getOrNull(selectedPage)?.id
    val originalOrder = remember(ordered) { ordered.sortedBy(UtilityPage::source) }
    fun updateOrder(newOrder: List<UtilityPage>) {
        model.utility.updatePages(id, newOrder)
        ordered = newOrder
        reordered(ordered)
    }
    LaunchedEffect(id, searchMatch, activePage) {
        if (searchMatch) ordered.indexOfFirst { it.id == activePage }.takeIf { it >= 0 }?.let { gridState.scrollToItem(it) }
    }
    BackHandler {if(selection.isNotEmpty()) selectedIds=emptyList() else if(!busy && !saving) back()}
    val backdrop = rememberDockBackdrop(true)
    val pageDetails = "${pages.size} pages · ${if(managed) "Folio PDF" else "Device PDF"}"
    val save = saveToFolio.takeIf { selection.isEmpty() }
    val saveLabel = if (saving) "Saving…" else "Save to Folio"
    Scaffold(topBar = {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val compactSaveWidth = maxWidth * .58f
            Column {
                FolioTopBar(title = { Column {
                    Text(if (selection.isEmpty()) title else "${selection.size} selected",
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compact) Text(pageDetails, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }, navigationIcon = {
                    IconButton(onClick = { if (selection.isNotEmpty()) selectedIds = emptyList() else back() },
                        enabled = !saving && !busy) {
                        Icon(if (selection.isEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Outlined.Close,
                            if (selection.isEmpty()) "Back" else "Clear PDF selection")
                    }
                }, actions = {
                    IconButton(onClick = { grid = !grid; preferences.edit { putBoolean("pdfGalleryGrid", grid) } },
                        enabled = !busy) {
                        Icon(if (grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView, if (grid) "List view" else "Grid view")
                    }
                    FolioOverflowButton("PDF gallery menu", { menu = true }, enabled = !busy, backdrop = backdrop)
                    if (!compact && save != null) FilledTonalButton(onClick = save, enabled = !saving && !busy) { Text(saveLabel) }
                })
                if (compact) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pageDetails, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (save != null) FilledTonalButton(onClick = save, enabled = !saving && !busy,
                        modifier = Modifier.widthIn(max = compactSaveWidth),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text(saveLabel) }
                }
            }
        }
        FolioOverflowMenu(menu,{menu=false},"PDF pages") {
            if (selection.isEmpty() && ordered != originalOrder) {
                FolioMenuItem("Reset to original page order", enabled = !busy && !saving, onClick = {
                    updateOrder(originalOrder)
                    menu = false
                })
                MenuSeparator()
            }
            FolioMenuItem(label="Select all pages",onClick={selectedIds=pages.map {it.id};menu=false})
            FolioMenuItem(label="Clear selection",enabled=selection.isNotEmpty(),onClick={selectedIds=emptyList();menu=false})
        }
    },bottomBar={FolioActionTray(Modifier.navigationBarsPadding(), backdrop) {
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotEmpty()) Text(message,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = when {
                maxWidth >= 270.dp * fontScale -> 3
                maxWidth >= 160.dp * fontScale -> 2
                else -> 1
            }
            val padding = PaddingValues(horizontal = 6.dp, vertical = 5.dp)
            val secondary = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .88f),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = columns,
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                FilledTonalButton(onClick={output(true)},enabled=!busy && !saving,
                    modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=padding) {
                    Icon(Icons.Outlined.Print,null,Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Print")
                }
                OutlinedButton(onClick={output(false)},enabled=!busy && !saving,
                    modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=padding,colors=secondary) {
                    Icon(Icons.Outlined.Share,null,Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Share")
                }
                OutlinedButton(onClick={message="";exportIds=selectedIds;destination.launch(pdfFileStem(title)+if(selectedIds.isEmpty()) ".pdf" else " selected.pdf")},
                    enabled=!busy && !saving,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=padding,colors=secondary) {
                    Icon(Icons.Outlined.SaveAlt,null,Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("Export")
                }
            }
        }
    }}) {padding ->
        ReorderablePageGrid(
            gridState, ordered.map { it.id }, padding.calculateBottomPadding(),
            Modifier.fillMaxSize().dockContentPadding(padding, true).then(backdrop?.source ?: Modifier).padding(horizontal = 16.dp),
            enabled = selection.isEmpty() && !busy,
            drop = { order ->
                updateOrder(order.map { key -> ordered.first { it.id == key } })
            },
            preview = { key ->
                ordered.firstOrNull { it.id == key }?.let { page ->
                    val bitmap by produceState<Bitmap?>(null, id, page) {
                        value = try { rendering.withLock { model.utility.galleryPreview(id, page) } }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { null }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                }
            }
        ) { dragging, target ->
            LazyVerticalGrid(if(grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1), Modifier.fillMaxSize().semantics { contentDescription="PDF page gallery" }, state=gridState,
                contentPadding=PaddingValues(top=16.dp,bottom=padding.calculateBottomPadding()+12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                itemsIndexed(ordered,key={_,page -> page.id}) {index,page ->
                var failed by remember(id,page) {mutableStateOf(false)}
                val image by produceState<Bitmap?>(null,id,page) {
                    try {value=rendering.withLock {model.utility.galleryPreview(id,page)}}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(_:Exception) {failed=true}
                }
                val checked=page.id in selection
                val selected=checked || selection.isEmpty() && page.id==activePage
                val matched = searchMatch && page.id == activePage
                val caption=if(dragging!=null && target==page.id && dragging!=page.id) "Move to position ${index+1}" else "Page ${index+1}"
                Column(Modifier.animateItem().graphicsLayer { alpha = if (dragging == page.id) .30f else 1f }) {
                    Surface(onClick={if(selection.isEmpty()) openPage(index) else toggle(page.id)},enabled=!busy,shape=MaterialTheme.shapes.medium,
                        color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border=BorderStroke(if(matched) 3.dp else if(selected || target==page.id) 2.dp else 1.dp,if(matched || selected || target==page.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier=Modifier.semantics {contentDescription="Open PDF page ${index+1}";this.selected=selected;stateDescription=if(failed) "Preview unavailable" else if(image==null) "Loading preview" else "Preview loaded"}) {
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Box((if(grid) Modifier.fillMaxWidth().aspectRatio(.75f) else Modifier.size(96.dp,128.dp)).padding(6.dp),contentAlignment=Alignment.Center) {
                            image?.let {Image(it.asImageBitmap(),"PDF page ${index+1} thumbnail",Modifier.fillMaxSize())}
                                ?: if(failed) Text("Preview unavailable",style=MaterialTheme.typography.bodySmall) else CircularProgressIndicator(Modifier.size(24.dp))
                            if (matched) SearchMatchBadge(Modifier.align(Alignment.BottomCenter).padding(4.dp), compact = !grid)
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
}

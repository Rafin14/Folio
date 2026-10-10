@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    BackHandler(onBack=back)
    Scaffold(topBar={TopAppBar(title={Column {
        Text(title,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text("${pages.size} pages · ${if(managed) "Folio PDF" else "Device PDF"}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},navigationIcon={IconButton(onClick=back,enabled=!saving) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}},actions={saveToFolio?.let {FilledTonalButton(onClick=it,enabled=!saving) {Text(if(saving) "Saving…" else "Save to Folio")}}})}) {padding ->
        LazyVerticalGrid(GridCells.Adaptive(150.dp),Modifier.fillMaxSize().padding(padding).onGloballyPositioned {gridBounds=it.boundsInRoot()}.semantics {contentDescription="PDF page gallery"},state=gridState,
            contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            itemsIndexed(ordered,key={_,page -> page.id}) {index,page ->
                DisposableEffect(page.id) {onDispose {bounds.remove(page.id)}}
                var failed by remember(id,page) {mutableStateOf(false)}
                val image by produceState<Bitmap?>(null,id,page) {
                    try {value=rendering.withLock {model.utility.preview(id,page,edge=480,includeAnnotations=true)}}
                    catch(cancel:CancellationException) {throw cancel}
                    catch(_:Exception) {failed=true}
                }
                val selected=page.id==activePage
                Column(Modifier.animateItem().onGloballyPositioned {bounds[page.id]=it.boundsInRoot()}.zIndex(if(dragging==page.id) 1f else 0f).graphicsLayer {if(dragging==page.id) {translationX=dragOffset.x;translationY=dragOffset.y+dragScroll;scaleX=1.04f;scaleY=1.04f}}.pageDragGesture(page.id,ordered.map {it.id},bounds,{dragging=it;dragScroll=0f;dragOrigin=it?.let {key ->bounds[key]?.center} ?: Offset.Zero},{target=it},{dragOffset=it},{},drop={order ->
                    ordered=order.map {key -> ordered.first {it.id==key}};model.utility.updatePages(id,ordered);reordered(ordered)
                })) {
                    Surface(onClick={openPage(index)},shape=MaterialTheme.shapes.medium,
                        color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border=BorderStroke(if(selected || target==page.id) 2.dp else 1.dp,if(selected || target==page.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier=Modifier.semantics {contentDescription="Open PDF page ${index+1}";this.selected=selected;stateDescription=if(failed) "Preview unavailable" else if(image==null) "Loading preview" else "Preview loaded"}) {
                        Box(Modifier.fillMaxWidth().aspectRatio(.75f).padding(6.dp),contentAlignment=Alignment.Center) {
                            image?.let {Image(it.asImageBitmap(),"PDF page ${index+1} thumbnail",Modifier.fillMaxSize())}
                                ?: if(failed) Text("Preview unavailable",style=MaterialTheme.typography.bodySmall) else CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    Text(if(dragging!=null && target==page.id && dragging!=page.id) "Move to position ${index+1}" else "Page ${index+1}",Modifier.padding(top=6.dp),style=MaterialTheme.typography.titleSmall,color=if(target==page.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

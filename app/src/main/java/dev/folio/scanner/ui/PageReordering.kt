package dev.folio.scanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A dedicated drag handle leaves long-press multi-selection on the thumbnail intact. */
@Composable
internal fun ReorderHandle(id:String,order:List<String>,bounds:Map<String,Rect>,target:(String?)->Unit,drop:(List<String>)->Unit,active:(String?)->Unit={}) {
    val currentOrder by rememberUpdatedState(order)
    val currentBounds by rememberUpdatedState(bounds)
    val currentDrop by rememberUpdatedState(drop)
    val currentTarget by rememberUpdatedState(target)
    val currentActive by rememberUpdatedState(active)
    var center by remember { mutableStateOf(Offset.Zero) }
    var destination by remember { mutableStateOf<String?>(null) }
    Icon(Icons.Outlined.DragHandle,"Drag to reorder",Modifier.size(48.dp).onGloballyPositioned { center=it.boundsInRoot().center }.semantics { contentDescription="Drag to reorder $id" }.pointerInput(id) {
        var point=Offset.Zero
        detectDragGestures(onDragStart={ currentActive(id); point=center; destination=id; currentTarget(id) },onDragCancel={ currentActive(null); destination=null; currentTarget(null) },onDragEnd={
            val from=currentOrder.indexOf(id); val to=currentOrder.indexOf(destination)
            if(from>=0 && to>=0 && from!=to) currentDrop(dev.folio.scanner.pdf.moved(currentOrder,from,to))
            currentActive(null); destination=null; currentTarget(null)
        }) { change,amount -> change.consume(); point+=amount; destination=currentBounds.entries.minByOrNull { (_,r) -> (r.center-point).getDistance() }?.key; currentTarget(destination) }
    }.padding(12.dp))
}

@Composable
internal fun Modifier.pageDragGesture(id:String,order:List<String>,bounds:Map<String,Rect>,active:(String?)->Unit,target:(String?)->Unit,offset:(Offset)->Unit,select:()->Unit,drop:(List<String>)->Unit):Modifier {
    val latestOrder by rememberUpdatedState(order); val latestBounds by rememberUpdatedState(bounds)
    val latestActive by rememberUpdatedState(active); val latestTarget by rememberUpdatedState(target)
    val latestOffset by rememberUpdatedState(offset); val latestSelect by rememberUpdatedState(select); val latestDrop by rememberUpdatedState(drop)
    return this.pointerInput(id) {
        var point=Offset.Zero; var start=Offset.Zero; var origin=Rect.Zero; var destination=id; var moved=false
        fun finish() { latestActive(null); latestTarget(null); latestOffset(Offset.Zero) }
        detectDragGesturesAfterLongPress(onDragStart={ p -> origin=latestBounds[id] ?: Rect.Zero; start=origin.topLeft+p; point=start; destination=id; moved=false; latestActive(id); latestTarget(id) },onDragCancel={ finish() },onDragEnd={
            val from=latestOrder.indexOf(id); val to=latestOrder.indexOf(destination)
            if(moved && from>=0 && to>=0 && from!=to) latestDrop(dev.folio.scanner.pdf.moved(latestOrder,from,to))
            else if(!moved) latestSelect()
            finish()
        }) { change,amount ->
            change.consume(); point+=amount; moved=moved || (point-start).getDistance()>viewConfiguration.touchSlop
            if(moved) { latestOffset(point-start); destination=latestBounds.entries.minByOrNull { (key,r) -> ((if(key==id) origin else r).center-point).getDistance() }?.key ?: id; latestTarget(destination) }
        }
    }
}

@Composable
internal fun ReorderList(order:List<String>,label:(String)->String,trailing:@Composable (String)->Unit={},drop:(List<String>)->Unit) {
    val bounds=remember { mutableStateMapOf<String,Rect>() }
    var target by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(order) { bounds.keys.retainAll(order.toSet()) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        order.forEach { id -> Surface(Modifier.fillMaxWidth().onGloballyPositioned { bounds[id]=it.boundsInRoot() },color=if(target==id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(if(target==id) 2.dp else 1.dp,if(target==id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),shape=MaterialTheme.shapes.small,shadowElevation=if(dragging==id) 8.dp else 0.dp) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { ReorderHandle(id,order,bounds,{ target=it },drop,{ dragging=it }); Text(label(id),Modifier.weight(1f).padding(vertical=12.dp,horizontal=8.dp),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium); trailing(id) }
        } }
    }
}

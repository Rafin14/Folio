package dev.folio.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

/** Bounded zoom/pan for quality inspection; processing continues using the retained original. */
@Composable
internal fun DocumentCanvas(image: Bitmap, description: String, modifier: Modifier=Modifier, rotation: Int=0, onPageSwipe:((Int)->Unit)?=null) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val inset=with(LocalDensity.current) { 4.dp.toPx() }
    fun transform(scale: Float, delta: Offset=Offset.Zero) {
        zoom=(zoom*scale).coerceIn(1f,6f)
        val aspect=if(rotation%180!=0) image.height.toFloat()/image.width else image.width.toFloat()/image.height
        val fitted=fit(bounds.width,bounds.height,aspect,inset)
        val maxX=(fitted[2]*zoom-bounds.width).coerceAtLeast(0f)/2; val maxY=(fitted[3]*zoom-bounds.height).coerceAtLeast(0f)/2
        pan=Offset((pan.x+delta.x).coerceIn(-maxX,maxX),(pan.y+delta.y).coerceIn(-maxY,maxY))
    }
    val swipe by rememberUpdatedState(onPageSwipe)
    val outline=MaterialTheme.colorScheme.outlineVariant
    val gestures=rememberTransformableState { scale,delta,_ -> transform(scale,delta) }
    Canvas(modifier.clipToBounds().onSizeChanged { bounds=androidx.compose.ui.geometry.Size(it.width.toFloat(),it.height.toFloat()) }.background(MaterialTheme.colorScheme.surfaceContainer).pointerInput(image) {
        detectTapGestures(onDoubleTap={ zoom=if(zoom>1f) 1f else 2f; pan=Offset.Zero })
    }.pointerInput(onPageSwipe!=null) {
        if(onPageSwipe==null) return@pointerInput
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            if(zoom>1f) return@awaitEachGesture
            var dx=0f;var dy=0f;var horizontal=false
            val threshold=minOf(size.width*.25f,160.dp.toPx()).coerceAtLeast(80.dp.toPx())
            while(true) {
                val event=awaitPointerEvent()
                if(event.changes.count { it.pressed }>1) break
                val change=event.changes.firstOrNull { it.id==down.id } ?: break
                if(change.isConsumed) break
                dx+=change.position.x-change.previousPosition.x;dy+=change.position.y-change.previousPosition.y
                if(!horizontal && kotlin.math.abs(dy)>viewConfiguration.touchSlop && kotlin.math.abs(dy)>kotlin.math.abs(dx)) break
                if(kotlin.math.abs(dx)>viewConfiguration.touchSlop && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) horizontal=true
                if(horizontal) change.consume()
                if(!change.pressed) {
                    if(horizontal && kotlin.math.abs(dx)>threshold) swipe?.invoke(if(dx<0) 1 else -1)
                    break
                }
            }
        }
    }.transformable(gestures,canPan={onPageSwipe==null || zoom>1f}).semantics {
        contentDescription=description
        stateDescription="Zoom ${"%.1f".format(java.util.Locale.US,zoom)} times"
        customActions=listOf(CustomAccessibilityAction("Zoom in") { transform(1.5f); true },CustomAccessibilityAction("Zoom out") { transform(1/1.5f); true },CustomAccessibilityAction("Reset zoom") { zoom=1f; pan=Offset.Zero; true })
    }) {
        val quarter=rotation%180!=0
        val aspect=if(quarter) image.height.toFloat()/image.width else image.width.toFloat()/image.height
        val r=fit(size.width,size.height,aspect,4.dp.toPx())
        val center=Offset(size.width/2+pan.x,size.height/2+pan.y)
        val w=(if(quarter) r[3] else r[2])*zoom; val h=(if(quarter) r[2] else r[3])*zoom
        rotate(rotation.toFloat(),center) {
            drawImage(image.asImageBitmap(),dstOffset=IntOffset((center.x-w/2).toInt(),(center.y-h/2).toInt()),dstSize=IntSize(w.toInt().coerceAtLeast(1),h.toInt().coerceAtLeast(1)))
            drawRect(outline,topLeft=Offset(center.x-w/2,center.y-h/2),size=androidx.compose.ui.geometry.Size(w,h),style=androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
    }
}

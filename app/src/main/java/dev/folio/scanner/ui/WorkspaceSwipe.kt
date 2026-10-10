package dev.folio.scanner.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Yield to children and vertical scroll; leave the system's back-gesture edges alone. */
internal fun Modifier.workspaceSwipe(enabled:Boolean,toWorkspace:Boolean,navigate:()->Unit):Modifier =
    if(!enabled) this else pointerInput(toWorkspace) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false)
            if(down.position.x<24.dp.toPx() || down.position.x>size.width-24.dp.toPx()) return@awaitEachGesture
            var dx=0f; var dy=0f; var horizontal=false
            val threshold=minOf(size.width*.25f,160.dp.toPx()).coerceAtLeast(80.dp.toPx())
            while(true) {
                val event=awaitPointerEvent()
                val change=event.changes.firstOrNull { it.id==down.id } ?: break
                if(change.isConsumed) break
                dx+=change.position.x-change.previousPosition.x
                dy+=change.position.y-change.previousPosition.y
                if(!horizontal && abs(dy)>viewConfiguration.touchSlop && abs(dy)>abs(dx)) break
                if(abs(dx)>viewConfiguration.touchSlop && abs(dx)>abs(dy)*1.5f) horizontal=true
                if(horizontal) change.consume()
                if(!change.pressed) {
                    if(horizontal && (if(toWorkspace) dx > threshold else dx < -threshold)) navigate()
                    break
                }
            }
        }
    }

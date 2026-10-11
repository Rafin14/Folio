package dev.folio.scanner.ui

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.*

@Composable internal fun SearchHighlightedText(text:String,ranges:List<IntRange>,active:IntRange?) {
    val colors=MaterialTheme.colorScheme
    val annotated=buildAnnotatedString {
        append(text)
        ranges.forEach {addStyle(SpanStyle(background=colors.secondaryContainer,color=colors.onSecondaryContainer),it.first,it.last+1)}
        active?.let {addStyle(SpanStyle(background=colors.primary,color=colors.onPrimary),it.first,it.last+1)}
    }
    val requester=remember {BringIntoViewRequester()}
    var layout by remember(text) {mutableStateOf<TextLayoutResult?>(null)}
    LaunchedEffect(active,layout) {if(active!=null) layout?.let {value ->
        withFrameNanos { };val first=value.getBoundingBox(active.first);val last=value.getBoundingBox(active.last)
        requester.bringIntoView(Rect(0f,first.top,value.size.width.toFloat(),last.bottom))
    }}
    Text(annotated,Modifier.bringIntoViewRequester(requester),onTextLayout={layout=it})
}

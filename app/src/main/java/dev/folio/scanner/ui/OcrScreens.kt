@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.content.*
import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.text.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.*
import dev.folio.scanner.ocr.*

@Composable
fun ExtractedTextScreen(document:String,page:String?,model:LibraryViewModel,back:()->Unit) {
    val context=LocalContext.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val pages by remember(document) { model.repository.dao.observePages(document) }.collectAsStateWithLifecycle(emptyList())
    val results by remember(document) { model.repository.dao.observeOcr(document) }.collectAsStateWithLifecycle(emptyList())
    val state by model.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf(page) }; var query by rememberSaveable { mutableStateOf("") }
    val scroll=rememberLazyListState()
    var current by rememberSaveable(query,selected) { mutableIntStateOf(0) }
    var rerun by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(document,page) { model.run { model.ocr.request(document,page) } }
    val shown=pages.filter { selected==null || it.id==selected }
    val text=shown.mapNotNull { p -> results.firstOrNull { it.pageId==p.id && validOcr(it,p) }?.text }.joinToString("\n\n")
    val matches=remember(shown,results,query) { shown.flatMap { p ->
        results.firstOrNull { it.pageId==p.id && validOcr(it,p) }?.let { r -> textMatches(r.text,query).map { p.id to it } }.orEmpty()
    } }
    val active=matches.getOrNull(current.coerceAtMost((matches.size-1).coerceAtLeast(0)))
    LaunchedEffect(active) { active?.let { match -> val index=shown.indexOfFirst { it.id==match.first }; if(index>=0) scroll.scrollToItem(index) } }
    Scaffold(modifier=Modifier.imePadding(),topBar={ TopAppBar(title={ Text(state.documents.firstOrNull { it.id==document }?.title ?: "Extracted text",maxLines=1) },navigationIcon={ IconButton(onClick=back) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } },actions={
        IconButton(enabled=text.isNotEmpty(),onClick={ (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Folio extracted text",text)) }) { Icon(Icons.Outlined.ContentCopy,"Copy All") }
        IconButton(enabled=text.isNotEmpty(),onClick={ context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Share extracted text")) }) { Icon(Icons.Outlined.Share,"Share extracted text") }
        IconButton(onClick={ rerun=true }) { Icon(Icons.Outlined.Refresh,"Re-run OCR") }
    }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected==null,{ selected=null },label={ Text("All pages") })
            pages.forEach { p -> FilterChip(selected==p.id,{ selected=p.id },label={ Text(p.label()) }) }
        }
        OutlinedTextField(query,{ query=it },singleLine=true,placeholder={ Text("Search within text") },leadingIcon={ Icon(Icons.Outlined.Search,null) },modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp))
        if(query.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(matches.isEmpty()) "0 matches" else "${matches.indexOf(active)+1} of ${matches.size}",Modifier.weight(1f))
            IconButton(enabled=matches.isNotEmpty(),onClick={ focus.clearFocus(); current=nextTextMatch(current,-1,matches.size) }) { Icon(Icons.Outlined.KeyboardArrowUp,"Previous match") }
            IconButton(enabled=matches.isNotEmpty(),onClick={ focus.clearFocus(); current=nextTextMatch(current,1,matches.size) }) { Icon(Icons.Outlined.KeyboardArrowDown,"Next match") }
        }
        LazyColumn(Modifier.weight(1f),state=scroll,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            items(shown,key={ it.id }) { p ->
                val result=results.firstOrNull { it.pageId==p.id }
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(p.label(),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                    when {
                        validOcr(result,p) -> {
                            val value=result!!.text
                            val ranges=remember(value,query) { textMatches(value,query) }
                            val focus=active?.takeIf { it.first==p.id }?.second
                            val background=MaterialTheme.colorScheme.secondaryContainer
                            val foreground=MaterialTheme.colorScheme.onSecondaryContainer
                            val strong=MaterialTheme.colorScheme.primary
                            val strongText=MaterialTheme.colorScheme.onPrimary
                            val annotated=remember(value,ranges,focus,background,strong,strongText,foreground) { buildAnnotatedString {
                                append(value)
                                ranges.forEach { r -> addStyle(SpanStyle(background=background,color=foreground),r.first,r.last+1) }
                                focus?.let { addStyle(SpanStyle(background=strong,color=strongText),it.first,it.last+1) }
                            } }
                            val requester=remember { BringIntoViewRequester() }
                            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                            LaunchedEffect(focus,layout) { if(focus!=null) layout?.let { l ->
                                withFrameNanos { }; withFrameNanos { }
                                val first=l.getBoundingBox(focus.first); val last=l.getBoundingBox(focus.last)
                                requester.bringIntoView(Rect(0f,first.top,l.size.width.toFloat(),last.bottom))
                            } }
                            if(value.isBlank()) Text("No text detected. This page is still available normally.")
                            else SelectionContainer { Text(annotated,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.bringIntoViewRequester(requester),onTextLayout={ layout=it }) }
                            if(dev.folio.scanner.BuildConfig.DEBUG) Text(result.diagnostics,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        result?.status in listOf("queued","processing") -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Recognizing text…",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        else -> {
                            Text(if(result?.status=="failed") "OCR couldn't be completed" else "Text needs recognition",color=MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick={ model.run { model.ocr.request(document,p.id,true) } }) { Text("Retry") }
                        }
                    }
                }
            }
        }
    } }
    if(rerun) AlertDialog(onDismissRequest={ rerun=false },title={ Text("Recognize text again?") },text={ Text("Replace extracted text for ${if(selected==null) "this document" else "this page"}. Your images stay unchanged.") },confirmButton={ TextButton(onClick={ rerun=false; model.run { model.ocr.request(document,selected,true) } }) { Text("Re-run OCR") } },dismissButton={ TextButton(onClick={ rerun=false }) { Text("Cancel") } })
}

@Composable
fun OcrSettingsScreen(model:LibraryViewModel,back:()->Unit) {
    val automatic by model.ocr.automatic.collectAsStateWithLifecycle(); var clear by remember { mutableStateOf(false) }
    Scaffold(topBar={ TopAppBar(title={ Text("Text recognition") },navigationIcon={ IconButton(onClick=back) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            ListItem(headlineContent={ Text("Automatic OCR") },supportingContent={ Text("Recognize newly accepted pages in the background") },trailingContent={ Switch(automatic,model.ocr::automatic) })
            Text("English printed text · PP-OCRv6_small",style=MaterialTheme.typography.titleMedium)
            Text("Recognition works offline. Handwriting, mathematics and other languages are not supported Folio capabilities. Authorized Drive backups include stored extracted text.",color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick={ model.run { model.ocr.retryFailed() } }) { Text("Retry failed OCR") }
            TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={ clear=true }) { Text("Clear OCR data") }
        }
    }
    if(clear) AlertDialog(onDismissRequest={ clear=false },title={ Text("Clear extracted text?") },text={ Text("Remove local text and its search index, pause automatic OCR, and cancel recognition. Document images are kept. Older Drive backups may still contain text.") },confirmButton={ TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={ clear=false; model.run { model.ocr.clear() } }) { Text("Clear OCR data") } },dismissButton={ TextButton(onClick={ clear=false }) { Text("Cancel") } })
}

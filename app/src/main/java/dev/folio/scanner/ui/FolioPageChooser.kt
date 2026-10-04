package dev.folio.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One compact read-only library browser, with single-page or ordered multi-page selection. */
@Composable
internal fun FolioPageChooser(model:LibraryViewModel,single:Boolean,dismiss:()->Unit,accept:(List<FolioPageChoice>)->Unit) {
    val library by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val allPages by produceState<List<Page>?>(null,library.documents) {
        value=withContext(Dispatchers.IO) { model.repository.dao.allPages().filter { it.trashedAt==null && library.documents.any { d -> d.id==it.documentId } } }
    }
    val pages=allPages.orEmpty()
    var documentId by rememberSaveable { mutableStateOf("") }
    var selectedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val selected=selectedIds.mapNotNull { id -> pages.find { it.id==id }?.let { FolioPageChoice(it.documentId,id) } }
    val document=library.documents.find { it.id==documentId }
    BackHandler { if(documentId.isNotEmpty()) documentId="" else dismiss() }
    val windowHeight=LocalWindowInfo.current.containerSize.height
    val maximumHeight=with(LocalDensity.current) { windowHeight.toDp()*.76f }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.widthIn(max=620.dp).fillMaxWidth(.90f).heightIn(max=maximumHeight),shape=MaterialTheme.shapes.large,
            color=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.97f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f)),tonalElevation=2.dp) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    if(documentId.isNotEmpty()) IconButton(onClick={ documentId="" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back to Folio documents") }
                    Text(document?.title ?: if(single) "Choose Folio page" else "Generate PDF",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                    IconButton(onClick=dismiss,enabled=!busy) { Icon(Icons.Outlined.Close,"Close Folio selection") }
                }
                Text(if(single) "Choose one page. Folio keeps the original." else "Check a document for all pages, or open it to choose pages. Numbers show PDF order.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(allPages==null) LinearProgressIndicator(Modifier.fillMaxWidth())
                val visible=if(documentId.isEmpty()) library.documents.filter { d -> pages.any { it.documentId==d.id } } else emptyList()
                if(allPages!=null && pages.isEmpty()) Text("No pages available",Modifier.padding(vertical=24.dp))
                LazyVerticalGrid(GridCells.Adaptive(120.dp),Modifier.weight(1f,fill=false).heightIn(min=100.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(documentId.isEmpty()) items(visible,key={ it.id }) { doc ->
                        val docPages=pages.filter { it.documentId==doc.id }.sortedBy { it.position }
                        val count=docPages.count { it.id in selectedIds }; val whole=count==docPages.size
                        Column {
                            Surface(onClick={ documentId=doc.id },shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.semantics { contentDescription="Open Folio document ${doc.title}" }) {
                                PageThumbnail(docPages.first(),model,"Document cover ${doc.title}",Modifier.fillMaxWidth().aspectRatio(.78f).padding(4.dp))
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                if(!single) Checkbox(whole,{ checked -> selectedIds=if(checked) selectedIds+docPages.map { it.id }.filter { it !in selectedIds } else selectedIds-docPages.map { it.id }.toSet() },Modifier.semantics { contentDescription="Select whole document ${doc.title}" })
                                Column(Modifier.weight(1f)) { Text(doc.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis); Text(if(count==0) "${docPages.size} pages" else if(whole) "All ${docPages.size} pages" else "$count pages selected",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    } else items(pages.filter { it.documentId==documentId }.sortedBy { it.position },key={ it.id }) { page ->
                        val position=selected.indexOfFirst { it.pageId==page.id }; val checked=position>=0
                        Column(Modifier.semantics { this.selected=checked }) {
                            Surface(onClick={ selectedIds=togglePageChoice(selected,FolioPageChoice(page.documentId,page.id),single).map { it.pageId } },shape=MaterialTheme.shapes.small,color=if(checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(if(checked) 2.dp else 1.dp,if(checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.semantics { contentDescription="Choose Folio ${page.label()}"; this.selected=checked }) {
                                Box {
                                    PageThumbnail(page,model,"Folio thumbnail ${page.label()}",Modifier.fillMaxWidth().aspectRatio(.78f).padding(4.dp))
                                    if(checked) Surface(Modifier.align(Alignment.TopEnd).padding(6.dp).semantics { contentDescription="Selection order ${position+1}" },shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) { Text("${position+1}",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelLarge) }
                                }
                            }
                            Text(page.label(),Modifier.padding(top=6.dp),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("${selected.size} ${if(single) "page" else "pages"} selected",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    Button(onClick={ accept(selected) },enabled=!busy && selected.isNotEmpty() && selected.size<=500) { Text(if(single) "Use selected page" else "Continue") }
                }
                if(selected.size>500) Text("Select at most 500 pages.",color=MaterialTheme.colorScheme.error)
            }
        }
    }
}

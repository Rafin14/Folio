@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.*

@Composable
internal fun RecycleBinScreen(model: LibraryViewModel,back: ()->Unit) {
    val docs by model.repository.trash.collectAsStateWithLifecycle(emptyList())
    val pages by model.repository.pageTrash.collectAsStateWithLifecycle(emptyList())
    val entries=docs.map { it.id }+pages.map { "page:${it.id}" }
    val busy by model.busy.collectAsStateWithLifecycle()
    val library by model.state.collectAsStateWithLifecycle()
    val driveDeletions by model.backups.deletions.collectAsStateWithLifecycle(emptyList())
    var restoreIds by remember { mutableStateOf(setOf<String>()) }
    var restoreNames by remember { mutableStateOf(mapOf<String,String>()) }
    var conflict by remember { mutableStateOf<Document?>(null) }
    var deletedHere by rememberSaveable { mutableStateOf(false) }
    var deletedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    fun restore(ids:Set<String>,names:Map<String,String> = emptyMap()) {
        val existing=library.documents.toMutableList()
        val colliding=docs.filter { it.id in ids }.firstOrNull { doc ->
            val name=names[doc.id] ?: doc.title
            if(documentNameConflict(name,existing)!=null) true else { existing+=doc.copy(title=name,trashedAt=null); false }
        }
        if(colliding!=null) { restoreIds=ids; restoreNames=names; conflict=colliding }
        else model.run {
            val docIds=ids.filter { !it.startsWith("page:") }.toSet()
            val pageIds=ids.filter { it.startsWith("page:") }.map { it.removePrefix("page:") }.toSet()
            if(docIds.isNotEmpty()) model.repository.restore(docIds,names)
            if(pageIds.isNotEmpty()) model.repository.restorePages(pageIds)
            selected=emptyList(); conflict=null
        }
    }
    var removal by rememberSaveable { mutableStateOf(listOf<String>()) }
    var emptying by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(entries) { selected=selected.filter { it in entries } }
    Scaffold(topBar={ TopAppBar(title={ Text("Recycle Bin") },navigationIcon={ IconButton(onClick=back) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } },actions={
        IconButton(enabled=entries.isNotEmpty() && !busy,onClick={ removal=entries; emptying=true }) { Icon(Icons.Outlined.DeleteForever,"Empty Recycle Bin") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text("Documents and pages are deleted permanently after 60 days.",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            val pending=driveDeletions.count { it.driveRemovalPending }
            if(pending>0) Text("Deleted from this device · $pending Google Drive removal${if(pending==1) "" else "s"} pending. Reconnect the backup account if needed.",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
            else if(deletedHere) Text(if(driveDeletions.any { it.state=="delete-complete" && it.hash in deletedIds }) "Deleted from this device · Associated backups removed from Google Drive" else "Deleted from this device",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
            if(driveDeletions.any { it.state=="delete-review" }) Text("A backup changed. Its Drive files have been kept for review.",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            if(entries.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                Checkbox(selected.size==entries.size,onCheckedChange={ selected=if(it) entries else emptyList() },enabled=!busy,modifier=Modifier.semantics { contentDescription="Select all recycled documents" })
                Text("${selected.size} selected")
                TextButton(enabled=selected.isNotEmpty() && !busy,onClick={ restore(selected.toSet()) }) { Text("Restore selected") }
                TextButton(enabled=selected.isNotEmpty() && !busy,onClick={ removal=selected; emptying=false }) { Text("Delete selected") }
            }
            if(entries.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { Text("Recycle Bin is empty",style=MaterialTheme.typography.titleMedium) }
            else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                items(pages,key={ "page:${it.id}" }) { page ->
                    val key="page:${page.id}"
                    Column {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(key in selected,onCheckedChange={ selected=if(it) selected+key else selected-key },enabled=!busy,modifier=Modifier.semantics { contentDescription="Select recycled page ${page.id}" })
                            androidx.compose.foundation.Image(bitmap=remember(page.thumbnailUri) { android.graphics.BitmapFactory.decodeFile(page.thumbnailUri)?.asImageBitmap() ?: androidx.core.graphics.createBitmap(1,1).asImageBitmap() },contentDescription="Recycled page thumbnail",modifier=Modifier.size(56.dp,76.dp).contentOutline())
                            Column(Modifier.weight(1f).padding(start=12.dp)) {
                                Text(page.pageName ?: "Page ${(page.trashPosition ?: 0)+1}",style=MaterialTheme.typography.titleMedium)
                                Text("Page from ${page.trashDocumentTitle}",style=MaterialTheme.typography.bodySmall)
                                Text("Original position ${(page.trashPosition ?: 0)+1} · ${page.width} × ${page.height}",style=MaterialTheme.typography.bodySmall)
                                Text("Deleted ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(requireNotNull(page.trashedAt)))}",style=MaterialTheme.typography.bodySmall)
                                if(docs.any { it.id==page.documentId }) Text("Restore the parent document first",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            TextButton(enabled=!busy && docs.none { it.id==page.documentId },onClick={ restore(setOf(key)) },modifier=Modifier.semantics { contentDescription="Restore page ${page.id}" }) { Text("Restore") }
                            TextButton(enabled=!busy,onClick={ removal=listOf(key); emptying=false },modifier=Modifier.semantics { contentDescription="Permanently delete page ${page.id}" }) { Text("Delete permanently") }
                        }
                        HorizontalDivider()
                    }
                }
                items(docs,key={ it.id }) { doc ->
                    Column {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(doc.id in selected,onCheckedChange={ selected=if(it) selected+doc.id else selected-doc.id },enabled=!busy,modifier=Modifier.semantics { contentDescription="Select ${doc.title}" })
                            DocumentCover(doc,model,Modifier.size(56.dp,76.dp))
                            Column(Modifier.weight(1f).padding(start=12.dp)) {
                                Text(doc.title,style=MaterialTheme.typography.titleMedium)
                                Text("${doc.pageCount} pages",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                val remaining=trashDaysRemaining(requireNotNull(doc.trashedAt),System.currentTimeMillis())
                                Text(if(remaining==0L) "Scheduled for permanent deletion" else "Deletes permanently in $remaining days",style=MaterialTheme.typography.bodySmall,color=if(remaining<=7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                            TextButton(enabled=!busy,onClick={ restore(setOf(doc.id)) },modifier=Modifier.semantics { contentDescription="Restore ${doc.title}" }) { Text("Restore") }
                            TextButton(enabled=!busy,onClick={ removal=listOf(doc.id); emptying=false },modifier=Modifier.semantics { contentDescription="Permanently delete ${doc.title}" }) { Text("Delete permanently") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    conflict?.let { doc -> NameDialog("Restore with another name","Document name",restoreNames[doc.id] ?: doc.title,busy,{ conflict=null },validate={ documentNameConflict(it,library.documents+docs.filter { d -> d.id in restoreIds && d.id!=doc.id }.map { d -> d.copy(title=restoreNames[d.id] ?: d.title,trashedAt=null) }) }) { name -> restore(restoreIds,restoreNames+(doc.id to name)) } }
    if(removal.isNotEmpty()) AlertDialog(onDismissRequest={ if(!busy) removal=emptyList() },title={ Text(if(emptying) "Empty Recycle Bin?" else "Delete permanently?") },text={ Text("${removal.size} selected items and their associated assets will be removed from this device. Associated Google Drive backups will also be removed when the account is connected. This cannot be undone.") },confirmButton={
        TextButton(enabled=!busy,onClick={ val ids=removal.toSet(); model.run { val docIds=ids.filter { !it.startsWith("page:") }.toSet(); val pageIds=ids.filter { it.startsWith("page:") }.map { it.removePrefix("page:") }.toSet(); if(docIds.isNotEmpty()) model.repository.permanentlyDelete(docIds); if(pageIds.isNotEmpty()) model.repository.permanentlyDeletePages(pageIds); model.backups.enqueueDeletions(); deletedHere=true; deletedIds=ids.toList(); removal=emptyList(); selected=emptyList() } }) { Text(if(emptying) "Empty Recycle Bin" else "Delete permanently") }
    },dismissButton={ TextButton(enabled=!busy,onClick={ removal=emptyList() }) { Text("Cancel") } })
}

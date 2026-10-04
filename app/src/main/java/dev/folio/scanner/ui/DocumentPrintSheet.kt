@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.print.PrintManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

@Composable
internal fun DocumentPrintSheet(doc:Document,pages:List<Page>,model:LibraryViewModel,selectionOnly:Boolean=false,dismiss:()->Unit) {
    val context=LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val jobs by remember(doc.id) { model.pdfs.jobs(doc.id) }.collectAsStateWithLifecycle(emptyList())
    var selectedMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(pages.map { it.id }) }
    var operation by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val job=jobs.firstOrNull { "operation-$operation" in it.tags }
    fun close() {
        if(operation.isNotEmpty() && job?.state?.isFinished!=true) job?.let { model.pdfs.work.cancelWorkById(it.id) }
        dismiss()
    }
    LaunchedEffect(job?.state,operation) {
        if(operation.isEmpty() || job?.state!=WorkInfo.State.SUCCEEDED) return@LaunchedEffect
        var snapshot:File?=null
        try {
            val asset=model.pdfs.results(operation).single()
            val prepared=File(context.cacheDir,"print-${UUID.randomUUID()}.pdf")
            snapshot=prepared; model.pdfs.snapshotResult(asset.id,prepared)
            val attributes=printAttributes(prepared)
            context.getSystemService(PrintManager::class.java).print(doc.title,FolioPrintAdapter(prepared,doc.title),attributes)
            snapshot=null; operation=""; dismiss()
        } catch(cancel:CancellationException) { throw cancel }
        catch(failure:Exception) { android.util.Log.e("Folio print","Android print handoff failed",failure); error="Could not open Android printing. Retry or use PDF export."; operation="" }
        finally { snapshot?.delete() }
    }
    ModalBottomSheet(onDismissRequest=::close) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Print document",style=MaterialTheme.typography.titleLarge)
            Text("Pages keep their proportions and fit the paper chosen in Android printing.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(operation.isNotEmpty()) {
                Text(when(job?.state) { WorkInfo.State.FAILED -> "Could not prepare PDF. Retry below."; WorkInfo.State.CANCELLED -> "Printing cancelled"; else -> "Preparing printable PDF" })
                if(job?.state?.isFinished!=true) {
                    val done=job?.progress?.getInt("done",0) ?: 0; val total=job?.progress?.getInt("total",0) ?: 0
                    if(total>0) { LinearProgressIndicator(progress={ done.toFloat()/total },modifier=Modifier.fillMaxWidth()); Text("$done of $total pages") }
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick={ job?.let { model.pdfs.work.cancelWorkById(it.id) } },enabled=job!=null) { Text("Cancel preparation") }
                } else TextButton(onClick={ operation="" }) { Text("Choose pages again") }
            } else {
                Row(Modifier.fillMaxWidth().selectable(!selectedMode,onClick={ selectedMode=false },role=androidx.compose.ui.semantics.Role.RadioButton),verticalAlignment=Alignment.CenterVertically) { RadioButton(!selectedMode,null); Text("${if(selectionOnly) "All selected pages" else "All pages"} (${pages.size})") }
                Row(Modifier.fillMaxWidth().selectable(selectedMode,onClick={ selectedMode=true },role=androidx.compose.ui.semantics.Role.RadioButton),verticalAlignment=Alignment.CenterVertically) { RadioButton(selectedMode,null); Text("Selected pages") }
                if(selectedMode) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("${selected.count { id -> pages.any { it.id==id } }} selected",Modifier.weight(1f))
                        TextButton(onClick={ selected=pages.map { it.id } }) { Text("Select all") }
                        TextButton(onClick={ selected=emptyList() }) { Text("Clear all") }
                    }
                    LazyColumn(Modifier.weight(1f,fill=false),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(pages,key={ it.id }) { page -> Row(verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(page.id in selected,{ checked -> selected=if(checked) selected+page.id else selected-page.id })
                            PageThumbnail(page,model,"Print preview ${page.label()}",Modifier.size(42.dp,56.dp))
                            Text(page.label(),Modifier.padding(start=12.dp))
                        } }
                    }
                }
                if(pages.isEmpty()) Text("Add pages before printing.")
                Button(onClick={ model.preparePdf {
                    operation=model.pdfs.start(doc.id,"generate",doc.title,quality=PdfQuality.ORIGINAL,selectedPages=if(selectedMode) selected.toSet() else if(selectionOnly) pages.map { it.id }.toSet() else null)
                } },enabled=!busy && pages.isNotEmpty() && (!selectedMode || pages.any { it.id in selected }),modifier=Modifier.fillMaxWidth()) { Text("Open Android print preview") }
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            TextButton(onClick=::close) { Text("Close") }
        }
    }
}

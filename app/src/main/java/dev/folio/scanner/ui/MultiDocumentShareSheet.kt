@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.folio.scanner.data.Document
import dev.folio.scanner.pdf.PdfQuality

/** Uses the same durable PDF jobs and FileProvider as single-document sharing. */
@Composable
internal fun MultiDocumentShareSheet(documents:List<Document>,model:LibraryViewModel,dismiss:()->Unit) {
    val context=LocalContext.current
    var combined by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("Combined document") }
    var quality by rememberSaveable { mutableStateOf(PdfQuality.BALANCED.name) }
    var operations by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var error by remember { mutableStateOf("") }
    var handoffAttempt by remember { mutableIntStateOf(0) }
    val owner=documents.first()
    val jobs by remember(owner.id) { model.pdfs.jobs(owner.id) }.collectAsStateWithLifecycle(emptyList())
    // Separate files have separate owners; watch all workers without starting a second job system.
    val allJobs by remember { model.pdfs.work.getWorkInfosByTagFlow("folio-pdf-share") }.collectAsStateWithLifecycle(emptyList())
    val observed=jobs+allJobs
    val requested=operations.map { id -> observed.firstOrNull { "operation-$id" in it.tags } }
    val expected=if(combined) 1 else documents.size
    val busy by model.busy.collectAsStateWithLifecycle()
    fun cancel() { requested.filterNotNull().filter { !it.state.isFinished }.forEach { model.pdfs.work.cancelWorkById(it.id) }; dismiss() }
    LaunchedEffect(operations,observed,handoffAttempt) {
        if(operations.size!=expected || requested.any { it?.state!=WorkInfo.State.SUCCEEDED }) return@LaunchedEffect
        try {
            val assets=operations.flatMap { model.pdfs.results(it) }
            check(assets.size==expected)
            context.startActivity(Intent.createChooser(pdfShareIntent(context,assets),"Share Folio documents"))
            operations=emptyList(); dismiss()
        } catch(cancel:kotlinx.coroutines.CancellationException) { throw cancel }
        catch(failure:Exception) { error="Could not open sharing. Retry sharing the prepared PDFs." }
    }
    ModalBottomSheet(onDismissRequest=::cancel) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Share ${documents.size} documents",style=MaterialTheme.typography.titleLarge)
            Text("Order: ${documents.joinToString(" → ") { it.title }}",style=MaterialTheme.typography.bodySmall)
            if(operations.isEmpty()) {
                FilterChip(!combined,{ combined=false },label={ Text("Share as multiple documents") })
                Text("One PDF per document, with its pages in their current order.",style=MaterialTheme.typography.bodySmall)
                FilterChip(combined,{ combined=true },label={ Text("Share as one document") })
                Text("One combined PDF in the selection order shown above.",style=MaterialTheme.typography.bodySmall)
                if(combined) OutlinedTextField(title,{ title=it },label={ Text("PDF name") },singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { PdfQuality.entries.forEach { q -> FilterChip(quality==q.name,{ quality=q.name },label={ Text(q.name.lowercase().replaceFirstChar { it.uppercase() }) }) } }
            } else {
                val complete=requested.count { it?.state==WorkInfo.State.SUCCEEDED }
                Text("$complete of $expected PDFs prepared")
                requested.filterNotNull().filter { !it.state.isFinished }.forEach { work ->
                    val done=work.progress.getInt("done",0); val total=work.progress.getInt("total",0)
                    if(total>0) { LinearProgressIndicator(progress={ done.toFloat()/total },modifier=Modifier.fillMaxWidth()); Text("$done of $total pages") }
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                requested.filterNotNull().filter { it.state==WorkInfo.State.FAILED || it.state==WorkInfo.State.CANCELLED }.forEach { work ->
                    Text("PDF preparation failed or was cancelled.")
                    TextButton(onClick={ model.preparePdf { model.pdfs.retry(operations.first { "operation-$it" in work.tags }) } }) { Text("Retry PDF") }
                }
            }
            if(error.isNotEmpty()) { Text(error,color=MaterialTheme.colorScheme.error); TextButton(onClick={ error=""; handoffAttempt++ }) { Text("Retry sharing") } }
            if(documents.any { it.pageCount==0 }) Text("Choose documents containing pages.",color=MaterialTheme.colorScheme.error)
            if(operations.size<expected) Button(onClick={ model.preparePdf {
                if(combined) operations=listOf(model.pdfs.start(owner.id,"generate",title,quality=PdfQuality.valueOf(quality),sourceDocuments=documents.map { it.id }))
                else documents.drop(operations.size).forEach { doc -> operations=operations+model.pdfs.start(doc.id,"generate",doc.title,quality=PdfQuality.valueOf(quality)) }
            } },enabled=!busy && documents.all { it.pageCount>0 } && (!combined || title.isNotBlank()),modifier=Modifier.fillMaxWidth()) { Text(if(operations.isEmpty()) "Prepare and share" else "Continue preparation") }
            TextButton(onClick=::cancel) { Text(if(operations.isEmpty()) "Cancel" else "Cancel preparation") }
        }
    }
}

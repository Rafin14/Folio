@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.PdfQuality
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

internal fun imageShareIntent(context: Context, files: List<File>, names: List<String>): Intent {
    require(files.isNotEmpty() && files.size == names.size)
    val root = File(context.cacheDir, "shared-images").canonicalFile.toPath()
    val uris = ArrayList(files.mapIndexed { i, file ->
        require(file.canonicalFile.toPath().startsWith(root) && file.isFile)
        FileProvider.getUriForFile(context, "${context.packageName}.files", file, names[i])
    })
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").apply {
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        clipData = ClipData.newRawUri("Document images", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal suspend fun exportImages(context: Context, files: List<File>, names: List<String>, tree: Uri) = withContext(Dispatchers.IO) {
    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    files.forEachIndexed { i, file ->
        ensureActive()
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "image/jpeg", names[i])) { "Could not create exported image." }
        try { context.contentResolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
        } } } catch(failure: Throwable) {
            withContext(NonCancellable) { runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) } }
            throw failure
        }
    }
}

@Composable
internal fun DocumentShareSheet(doc: Document, pages: List<Page>, model: LibraryViewModel, pageOnly: Boolean = false, dismiss: () -> Unit) {
    val context = LocalContext.current
    val busy by model.busy.collectAsStateWithLifecycle()
    val jobs by remember(doc.id) { model.pdfs.jobs(doc.id) }.collectAsStateWithLifecycle(emptyList())
var allPages by remember { mutableStateOf<List<Page>?>(null) }
    if(allPages!=null) { DocumentShareSheet(doc,allPages!!,model,dismiss=dismiss); return }
    var selected by rememberSaveable(doc.id) { mutableStateOf(pages.map { it.id }) }
    var initialized by rememberSaveable(doc.id) { mutableStateOf(pages.isNotEmpty()) }
    LaunchedEffect(pages) {
        if(!initialized && pages.isNotEmpty()) { selected=pages.map { it.id }; initialized=true }
        else selected=selected.filter { id -> pages.any { it.id==id } }
    }
    var quality by rememberSaveable { mutableStateOf(PdfQuality.BALANCED) }
    var password by remember { mutableStateOf("") }
    var imageJob by remember { mutableStateOf<Job?>(null) }
    var operation by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("") }
    var destination by rememberSaveable { mutableStateOf("") }
    val job = jobs.firstOrNull { "operation-$operation" in it.tags }
    val waiting = operation.isNotEmpty() && job?.state?.isFinished != true
    fun generate(action: String) { model.preparePdf { mode = action; operation = model.pdfs.start(doc.id, "generate", doc.title, quality = quality, password=password, selectedPages=selected.toSet()) } }
    fun images(tree: Uri? = null) { imageJob=model.run {
        model.repository.cleanShareCache()
        val folder = File(context.cacheDir, "shared-images/${UUID.randomUUID()}")
        var shared = false
        try {
            val files = model.repository.snapshotImages(doc.id, folder, selected.toSet())
            val title = doc.title.replace(Regex("[\\/:*?\"<>|]"), "_")
            val names = pages.filter { it.id in selected }.map { "$title - ${it.label().replace(Regex("[\\/:*?\"<>|]"), "_")}.jpg" }
            if (tree == null) { context.startActivity(Intent.createChooser(imageShareIntent(context, files, names), "Share document images")); shared = true }
            else exportImages(context, files, names, tree)
            dismiss()
        } finally { if (!shared) withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() } }
    } }
    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); destination = it.toString(); generate("export") }
    }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION); images(it) }
    }
    LaunchedEffect(job?.state, operation) {
        if (job?.state == WorkInfo.State.SUCCEEDED) {
            val completed = operation
            try {
                if (mode == "exporting") { operation = ""; dismiss() }
                else {
                    val asset = model.pdfs.results(completed).single()
                    if (mode == "share") { context.startActivity(Intent.createChooser(pdfShareIntent(context, asset), "Share document PDF")); operation = ""; dismiss() }
                    else { mode = "exporting"; operation = model.pdfs.start(doc.id, "export", doc.title, listOf(asset.id), destination = destination) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.e("Folio sharing","PDF handoff failed",failure); model.error.value = "Could not share or export PDF. Open PDF workspace to retry."; operation = "" }
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Share or export", style = MaterialTheme.typography.titleLarge)
            Text(if(pageOnly) pages.firstOrNull()?.label() ?: doc.title else doc.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (operation.isNotEmpty()) {
                Text(if (job?.state == WorkInfo.State.FAILED) "PDF operation failed. Retry in PDF workspace." else if (job?.state == WorkInfo.State.CANCELLED) "PDF operation cancelled" else "Preparing PDF")
                val done = job?.progress?.getInt("done", 0) ?: 0; val total = job?.progress?.getInt("total", 0) ?: 0
                if (waiting) {
                    if (total > 0) { LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth()); Text("$done of $total") }
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { job?.let { model.pdfs.work.cancelWorkById(it.id) } }, enabled = job != null) { Text("Cancel PDF") }
                } else TextButton(onClick = { operation = "" }) { Text("Choose another action") }
            } else {
                Text("PDF quality", style = MaterialTheme.typography.labelLarge)
                PdfQuality.entries.forEach { value -> Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(quality == value, { quality = value }); Text(when(value) { PdfQuality.ORIGINAL -> "Original"; PdfQuality.BALANCED -> "Balanced · 2400 px"; PdfQuality.SMALL -> "Small · 1600 px" })
                } }
                OutlinedTextField(password,{ password=it },label={ Text("PDF password (optional)") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true,isError=password.isNotEmpty() && password.length !in 4..64,modifier=Modifier.fillMaxWidth())
                Button(onClick = { generate("share") }, enabled = !busy && selected.isNotEmpty() && (password.isEmpty() || password.length in 4..64), modifier = Modifier.fillMaxWidth()) { Text("Share as PDF") }
                OutlinedButton(onClick = { exportPdf.launch(doc.title + ".pdf") }, enabled = !busy && selected.isNotEmpty() && (password.isEmpty() || password.length in 4..64), modifier = Modifier.fillMaxWidth()) { Text("Export PDF") }
                HorizontalDivider()
                Text("${selected.size} pages selected", style = MaterialTheme.typography.titleMedium)
                pages.forEach { page -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(page.id in selected, { checked -> selected = if (checked) selected + page.id else selected - page.id }); PageThumbnail(page,model,"Share preview ${page.label()}",Modifier.size(36.dp,48.dp)); Spacer(Modifier.width(8.dp)); Text(page.label(),Modifier.weight(1f))
                } }
                Button(onClick = { images() }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Share as Images") }
                OutlinedButton(onClick = { exportTree.launch(null) }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Export Images") }
                if(busy && imageJob?.isActive==true) TextButton(onClick={ imageJob?.cancel() }) { Text("Cancel image export") }
            }
            if(pageOnly && operation.isEmpty()) TextButton(onClick={ model.run { allPages=model.repository.dao.pages(doc.id) } },enabled=!busy) { Text("Share whole document") }
            TextButton(onClick = dismiss, enabled = !busy) { Text("Close") }
        }
    }
}

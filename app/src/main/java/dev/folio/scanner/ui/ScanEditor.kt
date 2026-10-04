package dev.folio.scanner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.ScanDraft

@Composable
fun ScanEditor(id: String, model: LibraryViewModel, finished: (String) -> Unit, retake: (String, String?) -> Unit, exit: () -> Unit) {
    var cropping by rememberSaveable(id) { mutableStateOf(true) }
    var discard by rememberSaveable(id) { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val saved = rememberSaveableStateHolder()
    var loaded by remember { mutableStateOf(false) }
    val draft by produceState<ScanDraft?>(null, id) { try { value = model.repository.draft(id) } catch (_: Exception) { model.error.value = "This unfinished scan is unavailable." } finally { loaded = true } }
    fun back() { if (!busy) { if (loaded && draft == null) exit() else discard = true } }
    fun retakeScan() { if (!busy) draft?.let { scan -> model.run { model.repository.discardDraft(id); retake(scan.documentId, scan.replacement) } } }
    BackHandler { back() }
    if (cropping) CropScreen(id, model, ::back, { cropping = false }, draft = true, retake = ::retakeScan)
    else saved.SaveableStateProvider("filters") {
        PageEditor(id, model, ::back, { cropping = true }, draft = true, retake = ::retakeScan, accepted = finished)
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard this scan?") }, text = { Text("Your current scan and edits haven't been saved.") },
        confirmButton = { TextButton(onClick = { draft?.let { scan -> model.run { model.repository.discardDraft(id); finished(scan.documentId) } } }, enabled = !busy && draft != null) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

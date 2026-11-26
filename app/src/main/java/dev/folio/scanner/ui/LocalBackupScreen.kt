package dev.folio.scanner.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.backup.LocalBackupArchive
import java.time.LocalDate

@Composable
internal fun BackupHome(back: () -> Unit, local: () -> Unit, drive: () -> Unit) {
    BackupPage("Backup", back) {
        FolioPanel {
            Column {
                val colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                ListItem(headlineContent = { Text("Local backup") },
                    supportingContent = { Text("Import or export a backup file") },
                    leadingContent = { Icon(Icons.Outlined.Folder, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = colors, modifier = Modifier.clickable(onClick = local))
                MenuSeparator()
                ListItem(headlineContent = { Text("Google Drive Backup") },
                    supportingContent = { Text("Back up or restore through Google Drive") },
                    leadingContent = { Icon(Icons.Outlined.CloudUpload, null) },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                    colors = colors, modifier = Modifier.clickable(onClick = drive))
            }
        }
    }
}

@Composable
internal fun LocalBackupScreen(model: LibraryViewModel, back: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val archive = remember(context, model) { LocalBackupArchive(context, model.repository, model.pipeline) }
    val running by model.localBackupRunning.collectAsStateWithLifecycle()
    val feedback by model.localBackupFeedback.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) model.runLocalBackup(isImport = false) { archive.export(uri) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri?.toString() }
    BackupPage("Local backup", back) {
        FolioPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", style = MaterialTheme.typography.titleMedium)
                Text("Save documents, original scans, and extracted text to a ZIP file. The file is not encrypted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = { export.launch("Folio-backup-${LocalDate.now()}.zip") }) {
                    Text("Export backup")
                }
            }
        }
        FolioPanel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Import", style = MaterialTheme.typography.titleMedium)
                Text("Add documents from a Folio backup ZIP without replacing your current documents.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = !busy, onClick = {
                    picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                }) { Text("Import backup") }
            }
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Working on local backup…")
            TextButton(onClick = model::cancelLocalBackup) { Text("Cancel backup") }
        }
        feedback?.let { Text(it) }
    }
    pending?.let { uri ->
        FolioDialog(onDismissRequest = { pending = null }, title = { Text("Import local backup?") },
            text = { Text("Folio will verify the archive before adding documents. If a name already exists, rename the existing document and retry.") },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    pending = null
                    model.runLocalBackup(isImport = true) { archive.restore(Uri.parse(uri)) }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}

@Composable
private fun BackupPage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(topBar = { FolioTopBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

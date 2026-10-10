@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.backup.*
import dev.folio.scanner.data.driveRemovalPending
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen(repository:FolioBackupRepository,back:()->Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val deletions by repository.deletions.collectAsStateWithLifecycle(emptyList())
    val removalErrors by repository.removalErrors.collectAsStateWithLifecycle(emptyList())
    val removing by repository.removalsRunning.collectAsStateWithLifecycle()
    val cloudPurges by repository.cloudPurges.collectAsStateWithLifecycle(emptyList())
    val cloudPurge=cloudPurges.firstOrNull { it.key=="${state.email}:cloud-purge" }
    val cloudBlocked=cloudPurge!=null && cloudPurge.state!="cloud-active"
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }; var error by rememberSaveable { mutableStateOf("") }
    var pendingEmail by rememberSaveable { mutableStateOf("") }
    var inspecting by rememberSaveable { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    fun action(block:suspend()->Unit) { scope.launch {
        busy=true; error=""
        try { block() } catch(cancel:CancellationException) { throw cancel }
        catch(e:Exception) { error=when(e) { is IllegalArgumentException,is IllegalStateException,is DriveFailure,is BackupAuthRequired -> e.message.orEmpty(); else -> "Google could not complete this action. Check connection and account setup, then try again." } }
        finally { busy=false }
    } }
    val consent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if(result.resultCode==Activity.RESULT_OK && result.data!=null && pendingEmail.isNotEmpty()) action {
            repository.auth.complete(result.data!!)
            withContext(Dispatchers.IO) {
                if(inspecting) { require(pendingEmail==state.email) { "Google account changed. Check inspection access again." }; repository.refreshInspectionAuthorization(); if(cloudPurge!=null && cloudPurge.state !in listOf("cloud-active","cloud-purged")) repository.retryCloudPurge() }
                else repository.connected(pendingEmail)
            }; pendingEmail=""; inspecting=false
        } else { pendingEmail=""; error="Google Drive permission was not granted. Your documents remain on this device." }
    }
    Scaffold(topBar={ TopAppBar(title={ Text("Google Drive Backup") },navigationIcon={ IconButton(onClick=back) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.CloudDone,null,tint=MaterialTheme.colorScheme.primary)
                Column { Text(state.status,style=MaterialTheme.typography.titleMedium); Text(state.email.ifEmpty { "Backup is optional" },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val accountDeletions=deletions.filter { it.key.startsWith("${state.email}:delete:") }
            val pendingRemovals=accountDeletions.count { it.driveRemovalPending }
            if(pendingRemovals>0) {
                Text("$pendingRemovals Google Drive removal${if(pendingRemovals==1) "" else "s"} pending. Documents are already deleted from this device.",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={ action { repository.retryDeletions() } },enabled=!busy && !removing && state.email.isNotEmpty() && !cloudBlocked) { Text(if(removing) "Removing Drive backups…" else "Retry Drive removals") }
                removalErrors.filter { it.key.startsWith("${state.email}:delete-error:") }.take(3).forEach { Text(it.session,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            } else if(accountDeletions.any { it.state=="delete-complete" }) {
                Text("Drive removal verified for ${accountDeletions.count { it.state=="delete-complete" }} permanently deleted item(s). Other cloud backups may remain.",style=MaterialTheme.typography.bodySmall)
            }
            if(state.email.isEmpty() || state.error.contains("Reconnect",ignoreCase=true)) {
                OutlinedButton(enabled=!busy,onClick={ action {
                    pendingEmail=repository.auth.selectAccount(context)
                    inspecting=false
                    val authorization=repository.auth.connect(pendingEmail)
                    if(authorization.pendingIntent!=null) consent.launch(IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build())
                    else { withContext(Dispatchers.IO) { repository.connected(pendingEmail) }; pendingEmail="" }
                } },modifier=Modifier.fillMaxWidth()) { Text("Sign in with Google") }
            }
            if(state.email.isNotEmpty()) {
                HorizontalDivider()
                if(!state.backupUnlocked) Text("Backup is locked on this installation. Restore your existing Folio backup successfully first. Signing in does not upload or replace your Drive backup.",style=MaterialTheme.typography.bodyMedium)
                BackupToggle("Automatic backup",if(!state.backupUnlocked) "Restore first to unlock uploads" else if(cloudBlocked) "Paused by cloud deletion. Back Up Now can restart after completion." else "Upload changes in the background",state.automatic,!busy && state.backupUnlocked && !cloudBlocked) { enabled ->
                    if(enabled) confirmation="automatic" else repository.automatic(false)
                }
                BackupToggle("Wi-Fi only","Wait for an unmetered connection",state.wifiOnly,!busy && !state.running,repository::wifiOnly)
                HorizontalDivider()
                Text("Last successful backup",style=MaterialTheme.typography.labelLarge)
                Text(if(state.lastBackup==0L) "No backup yet" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(state.lastBackup)))
                Text("${state.pending} document${if(state.pending==1) "" else "s"} pending",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(state.running) {
                    LinearProgressIndicator(progress={ if(state.total==0) 0f else state.done.toFloat()/state.total },modifier=Modifier.fillMaxWidth())
                    Text("${state.done} of ${state.total} steps",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=repository::cancel) { Text("Cancel and pause backup") }
                } else {
                    Button(onClick={ confirmation="manual" },enabled=!busy && state.backupUnlocked && (!cloudBlocked || cloudPurge?.state=="cloud-purged"),modifier=Modifier.fillMaxWidth()) { Text("Back Up Now") }
                    OutlinedButton(onClick={ action { preview=repository.discover(); confirmation="restore" } },enabled=!busy && !cloudBlocked,modifier=Modifier.fillMaxWidth()) { Text("Find backup to restore") }
                    if(state.recoverable && !state.error.contains("Reconnect",ignoreCase=true)) TextButton(onClick={ action { withContext(Dispatchers.IO) { repository.retry() } } },enabled=!busy) { Text("Retry interrupted operation") }
                }
                TextButton(onClick={ confirmation="disconnect" },enabled=!busy) { Text("Disconnect Google Account") }
                CloudPurgeSection(repository,state.email,cloudPurge) {
                    action {
                        pendingEmail=state.email
                        inspecting=true
                        val authorization=repository.auth.inspectForPurge(pendingEmail)
                        if(authorization.pendingIntent!=null) consent.launch(IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build())
                        else { repository.refreshInspectionAuthorization(); if(cloudPurge!=null && cloudPurge.state !in listOf("cloud-active","cloud-purged")) repository.retryCloudPurge(); pendingEmail=""; inspecting=false }
                    }
                }
            }
            val message=error.ifEmpty { state.error }
            if(message.isNotEmpty()) Text(message,color=if(state.status=="Backup pending") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
            HorizontalDivider()
            Text("Local first",style=MaterialTheme.typography.titleSmall)
            Text("Folio works offline. After you authorize backup, originals, edited pages, stored extracted text and document metadata—including Recycle Bin—are uploaded to your visible Folio Backup folder. Temporary OCR images, analytics and unrelated files are excluded.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Drive transfer uses HTTPS. Backups are not encrypted by Folio before upload.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(confirmation.isNotEmpty() && (confirmation!="restore" || preview!=null)) {
        val mode=confirmation
        AlertDialog(onDismissRequest={ confirmation=""; preview=null },title={ Text(when(mode) { "automatic" -> "Enable automatic backup?"; "manual" -> "Back up your Folio library?"; "disconnect" -> "Disconnect Google Account?"; else -> "Restore this backup?" }) },text={ Text(when(mode) {
            "automatic","manual" -> "Upload all saved documents, retained originals, edited images, stored extracted text, folders and Recycle Bin to ${state.email}. Future changes upload automatically only if you enable automatic backup."
            "disconnect" -> "Backups will stop. Local documents and the existing Drive backup will be kept."
            else -> preview!!.let { "${it.manifest.documents.size} documents · ${it.manifest.pages.size} pages\n${DateFormat.getDateTimeInstance().format(Date(it.manifest.createdAt))}\n\nAll files are validated first. Existing local documents are kept. Active document names must be unique; rename a conflicting local document before restoring." }
        }) },confirmButton={ TextButton(onClick={
            val selected=preview; confirmation=""; preview=null
            action { withContext(Dispatchers.IO) { when(mode) {
                "automatic" -> repository.automatic(true)
                "manual" -> repository.backUpNow()
                "disconnect" -> repository.disconnect()
                else -> selected?.let { repository.restore(it) }
            } } }
        }) { Text(if(mode=="disconnect") "Disconnect" else if(mode=="restore") "Restore" else "Authorize upload") } },dismissButton={ TextButton(onClick={ confirmation=""; preview=null }) { Text("Cancel") } })
    }
}

@Composable private fun BackupToggle(title:String,subtitle:String,checked:Boolean,enabled:Boolean,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title,style=MaterialTheme.typography.bodyLarge); Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked=checked,onCheckedChange=change,enabled=enabled,modifier=Modifier.semantics { contentDescription=title })
    }
}

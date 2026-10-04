package dev.folio.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.folio.scanner.backup.*
import dev.folio.scanner.data.BackupRecord
import kotlinx.coroutines.*

@Composable internal fun CloudPurgeSection(repository:FolioBackupRepository,email:String,receipt:BackupRecord?,inspect:()->Unit) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    val work by remember(email) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("folio-cloud-purge-$email") }.collectAsStateWithLifecycle(emptyList())
    val executing=work.any { it.state==WorkInfo.State.RUNNING }
    var stage by rememberSaveable(email) { mutableIntStateOf(0) }
    var typed by rememberSaveable(email) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf("") }
    var details by remember { mutableStateOf<List<BackupRecord>?>(null) }
    fun action(block:suspend()->Unit) { scope.launch {
        busy=true; error=""
        try { withContext(Dispatchers.IO) { block() } } catch(cancel:CancellationException) { throw cancel }
        catch(failure:Exception) { error=removalReason(failure) } finally { busy=false }
    } }
    val progress=CloudPurgeProgress.read(receipt)
    val pending=receipt!=null && receipt.state !in listOf("cloud-active","cloud-purged")
    HorizontalDivider()
    Text("Cloud backup management",style=MaterialTheme.typography.titleSmall)
    Text("Permanently remove only Folio's cloud backup. Documents on this device stay intact.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(receipt!=null && receipt.state!="cloud-active") {
        Text(when(receipt.state) { "cloud-purged" -> "Cloud backup deleted"; "purge-running","purge-verified" -> "Deleting cloud backup…"; "purge-review" -> "Cloud backup deletion requires review"; "purge-failed" -> "Some cloud files could not be deleted"; else -> "Cloud backup deletion is pending" },style=MaterialTheme.typography.titleMedium)
        if(progress.total>0) {
            if(pending) LinearProgressIndicator(progress={ (progress.deleted+progress.absent).toFloat()/progress.total },modifier=Modifier.fillMaxWidth())
            Text("${progress.deleted} deleted · ${progress.absent} already absent · ${progress.pending} pending · ${progress.failed} failed · ${progress.review} require review",style=MaterialTheme.typography.bodySmall)
        } else if(pending) Text("Waiting for connectivity and Google authorization to inventory the backup.",style=MaterialTheme.typography.bodySmall)
        if(progress.message.isNotEmpty()) Text(progress.message,style=MaterialTheme.typography.bodySmall)
        if(pending) {
            Row {
                TextButton(onClick={ action { repository.retryCloudPurge() } },enabled=!busy && !executing) { Text("Retry cloud deletion") }
                TextButton(onClick={ action { details=repository.purgeDetails() } },enabled=!busy) { Text("View details") }
            }
        } else Text("Automatic backup remains paused. Back Up Now can intentionally create a new backup.",style=MaterialTheme.typography.bodySmall)
    }
    if(receipt?.state!="cloud-purged") TextButton(onClick=inspect,enabled=!busy && !executing) { Text("Authorize safe folder inspection") }
    TextButton(onClick={ typed=""; stage=1 },enabled=!busy && !pending,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Delete All Cloud Backup") }
    if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    if(stage==1) AlertDialog(onDismissRequest={ stage=0 },title={ Text("Delete all cloud backup?") },text={ Text("Permanently delete all Folio backup data in Google Drive for $email. Your local Folio documents and Android files will not be deleted. Unrelated Drive files are kept. This cannot be undone.") },confirmButton={ TextButton(onClick={ stage=2 }) { Text("Continue") } },dismissButton={ TextButton(onClick={ stage=0 }) { Text("Cancel") } })
    if(stage==2) AlertDialog(onDismissRequest={ stage=0; typed="" },title={ Text("Type DELETE to confirm") },text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Account: $email\nLocal documents stay intact. This cloud deletion cannot be undone.")
        Text("Google may ask for read-only Drive metadata access so Folio can detect unrelated files inside backup folders. Normal backup permissions are unchanged.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(typed,{ typed=it },label={ Text("Confirmation") },supportingText={ Text("Exact uppercase DELETE; case-sensitive.") },singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={ focus.clearFocus() }),modifier=Modifier.fillMaxWidth())
    } },confirmButton={ TextButton(enabled=validPurgeConfirmation(typed) && !busy,onClick={
        val confirmation=typed; val account=email; stage=0; typed=""
        action { repository.requestCloudPurge(account,confirmation); withContext(Dispatchers.Main) { inspect() } }
    },colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Permanently Delete Cloud Backup") } },dismissButton={ TextButton(onClick={ stage=0; typed="" }) { Text("Cancel") } })
    details?.let { entries -> AlertDialog(onDismissRequest={ details=null },title={ Text("Cloud deletion details") },text={ Text(if(entries.isEmpty()) "No individual failures are recorded. Check connectivity and authorize folder inspection if requested." else entries.take(8).joinToString("\n\n") { "${it.state}: ${it.remoteId}\n${it.session.ifEmpty { "Waiting for removal or final verification." }}" }+if(entries.size>8) "\n\n${entries.size-8} more resources remain." else "",modifier=Modifier.verticalScroll(rememberScrollState())) },confirmButton={ TextButton(onClick={ details=null }) { Text("Close") } }) }
}

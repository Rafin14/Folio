package dev.folio.scanner.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.edit
import androidx.room.withTransaction
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

data class BackupState(val email:String="",val automatic:Boolean=false,val wifiOnly:Boolean=true,
    val status:String="Not connected",val lastBackup:Long=0,val pending:Int=0,val error:String="",val done:Int=0,val total:Int=0,val running:Boolean=false,val recoverable:Boolean=false,val backupUnlocked:Boolean=false)
data class RestorePreview(val remoteId:String,val checksum:String,val manifest:BackupManifest,val rootId:String="")

/** Protects resumable upload capability URLs at rest; OAuth tokens never enter local storage. */
class UploadSessionVault {
    private fun cipher(mode:Int,iv:ByteArray?=null):Cipher {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; val alias="folio-drive-upload-sessions"
        val key=(store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { if(iv==null) init(mode,key) else init(mode,key,GCMParameterSpec(128,iv)) }
    }
    fun seal(value:String):String { val c=cipher(Cipher.ENCRYPT_MODE); return android.util.Base64.encodeToString(c.iv+c.doFinal(value.toByteArray()),android.util.Base64.NO_WRAP) }
    fun open(value:String):String { val data=android.util.Base64.decode(value,android.util.Base64.NO_WRAP); require(data.size>28); return cipher(Cipher.DECRYPT_MODE,data.copyOfRange(0,12)).doFinal(data.copyOfRange(12,data.size)).toString(Charsets.UTF_8) }
}

@Singleton
class FolioBackupRepository @Inject constructor(@ApplicationContext private val context:Context,
    private val database:FolioDatabase,private val documents:DocumentRepository,val auth:DriveAuth,private val pipeline:ImagePipeline) {
    private val prefs=context.getSharedPreferences("drive-backup",Context.MODE_PRIVATE)
    // Installation-scoped: account disconnect must not reset the restore requirement.
    private val safety=context.getSharedPreferences("drive-backup-safety",Context.MODE_PRIVATE)
    init {
        // Pin a fresh installation before background DB initialization or a first capture can race migration.
        val name=database.openHelper.databaseName
        if(!safety.contains("unlocked") && prefs.all.isEmpty() && name!=null && !context.getDatabasePath(name).exists()) {
            check(safety.edit().putBoolean("unlocked",false).commit()) { "Could not save backup safety state." }
        }
    }
    private val safetyLock=Mutex()
    internal suspend fun initializeSafety() = safetyLock.withLock {
        if(!safety.contains("unlocked")) {
            val existing=dao.allDocuments().isNotEmpty() || prefs.getInt("backupDataVersion",1)>=2 ||
                prefs.getLong("lastBackup",0)>0 || prefs.getBoolean("automatic",false) ||
                dao.backupRecords("restore:").any { it.state=="complete" && it.bytes>0 }
            check(safety.edit().putBoolean("unlocked",existing).commit()) { "Could not save backup safety state." }
        }
        update { if(!safety.getBoolean("unlocked",false)) putBoolean("automatic",false) }
    }
    private fun requireBackupUnlocked() { check(state.value.backupUnlocked) { "Restore your existing Folio backup successfully before uploading from this installation." } }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _,error ->
        if(error !is CancellationException) failure(Exception("Local backup scheduling failed."),false)
    })
    private val work get()=WorkManager.getInstance(context)
    private val dao get()=database.documents()
    private val vault=UploadSessionVault()
    private var started=false
    @Volatile private var queued=false
    private val operationLock=Mutex()
    val state=MutableStateFlow(readState())
    val deletions=dao.observeDriveDeletions()
    val cloudPurges=dao.observeCloudPurges()
    val removalErrors=dao.observeRemovalErrors()
    val removalsRunning=MutableStateFlow(false)
    val inspectionAuthorized=MutableStateFlow<Boolean?>(null)
    suspend fun refreshInspectionAuthorization() {
        val email=state.value.email
        inspectionAuthorized.value=null
        if(email.isEmpty()) return
        val granted=auth.inspectionAuthorized(email)
        if(state.value.email==email) {
            check(prefs.edit().putBoolean("inspection:$email",granted).commit())
            inspectionAuthorized.value=granted
        }
    }
    @Volatile private var cloudBarrier=false
    private suspend fun cloudBlocked(email:String=state.value.email):Boolean {
        val blocked=dao.backupRecord(purgeKey(email))?.blocksBackup()==true
        if(email==state.value.email) cloudBarrier=blocked
        return blocked
    }
    internal suspend fun pendingDeletions(email:String)=dao.driveDeletions().count { it.state=="delete-pending" && it.key.startsWith("$email:delete:") }
    private fun readState()=BackupState(prefs.getString("email","").orEmpty(),prefs.getBoolean("automatic",false) && safety.getBoolean("unlocked",false),prefs.getBoolean("wifi",true),
        prefs.getString("status","Not connected").orEmpty(),prefs.getLong("lastBackup",0),prefs.getInt("pending",0),prefs.getString("error","").orEmpty(),prefs.getInt("done",0),prefs.getInt("total",0),prefs.getBoolean("running",false),prefs.getBoolean("recoverable",false),safety.getBoolean("unlocked",false))
    @Synchronized private fun update(block:android.content.SharedPreferences.Editor.()->Unit) { prefs.edit(commit=true,action=block); state.value=readState() }
    @Synchronized private fun updateFor(expected:String,block:android.content.SharedPreferences.Editor.()->Unit) {
        if(epoch()!=expected || state.value.email.isEmpty()) return
        update(block)
    }
    @OptIn(FlowPreview::class)
    fun start() {
        if(started) return; started=true
        scope.launch {
            database.invalidationTracker.createFlow("documents","pages","folders","ocr",emitInitialState=true).debounce(500).collect {
                refresh(); if(state.value.automatic && state.value.pendingChanges()) enqueueAutomatic()
            }
        }
        scope.launch {
            work.getWorkInfosByTagFlow(TAG).collect { infos ->
                val active=infos.any { it.state==WorkInfo.State.RUNNING }
                queued=infos.any { it.state in listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED) && PERIODIC !in it.tags }
                update { putBoolean("running",active); if(queued && !active && state.value.email.isNotEmpty() && state.value.error.isEmpty()) putString("status","Backup pending") }
                if(!active && state.value.status in listOf("Backing up…","Restoring…")) refresh()
            }
        }
        scope.launch {
            cloudPurges.collect { records ->
                val current=records.firstOrNull { it.key==purgeKey(state.value.email) }
                cloudBarrier=current?.blocksBackup()==true
                if(current!=null && current.state !in listOf("cloud-purged","cloud-active","purge-review")) enqueuePurge(state.value.email)
            }
        }
        scope.launch {
            if(prefs.getInt("removalProtocolVersion",1)<2) {
                dao.driveDeletions().filter { it.state=="delete-review" }.forEach { dao.save(it.copy(state="delete-pending")) }
                update { putInt("removalProtocolVersion",2) }
                enqueueDeletions()
            }
        }
        scope.launch {
            initializeSafety()
            if(prefs.getInt("backupDataVersion",1)<2 && state.value.automatic) {
                update { putBoolean("automatic",false); putString("status","Backup paused"); putString("error","Backups can now include extracted text. Authorize upload again to enable automatic backup.") }
                work.cancelUniqueWork(PERIODIC); work.cancelAllWorkByTag(AUTO_TAG)
            }
            if(state.value.automatic && !cloudBarrier) schedulePeriodic()
            else { work.cancelUniqueWork(PERIODIC); work.cancelAllWorkByTag(AUTO_TAG) }
        }
        scope.launch {
            dao.observeDriveDeletions().map { records -> records.map { it.key }.toSet() }.distinctUntilChanged().collect {
                try { cleanDeletedStaging() } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) { android.util.Log.w("Folio Drive","Local staged cleanup will retry",error) }
                if(dao.driveDeletions().any { it.driveRemovalPending && it.key.startsWith("${state.value.email}:delete:") }) enqueueDeletions()
            }
        }
    }
    private fun BackupState.pendingChanges()=pending>0 || prefs.getString("currentFingerprint","")!=prefs.getString("lastFingerprint","")
    suspend fun refresh() {
        initializeSafety()
        val email=state.value.email; val expected=epoch(); if(email.isEmpty()) return
        if(cloudBlocked(email)) {
            val receipt=dao.backupRecord(purgeKey(email))
            updateFor(expected) { putBoolean("automatic",false); putBoolean("running",false); putString("status",if(receipt?.state=="cloud-purged") "Cloud backup purged" else "Cloud backup deletion pending") }
            return
        }
        val snapshot=documents.librarySnapshot(); val fingerprint=BackupPlanner.fingerprint(snapshot)
        var pending=0
        snapshot.documents.forEach { doc ->
            val hash=BackupPlanner.documentFingerprint(snapshot,doc)
            if(dao.backupRecord("$email:document:${doc.id}")?.hash!=hash) pending++
        }
        val changed=fingerprint!=prefs.getString("lastFingerprint","")
        if(!isCurrent(expected)) return
        updateFor(expected) {
            putInt("pending",pending); putString("currentFingerprint",fingerprint)
            if(!state.value.running && state.value.error.isEmpty()) putString("status",BackupPlanner.status(true,false,changed,state.value.automatic || queued))
        }
    }
    /** Called only after foreground Google account selection AND drive.file authorization succeed. */
    suspend fun connected(email:String) {
        initializeSafety()
        require(email.contains('@'))
        val old=state.value.email
        if(old!=email) {
            inspectionAuthorized.value=null
            work.cancelAllWorkByTag(TAG).result.get()
            work.cancelAllWorkByTag(REMOVAL_TAG).result.get()
            update { clear(); putString("epoch",UUID.randomUUID().toString()); putBoolean("wifi",true) }
        }
        update { putString("email",email); putString("error",""); putString("status","Backup paused") }
        refresh()
        enqueueDeletions()
        if(cloudBlocked(email)) enqueuePurge(email)
    }
    suspend fun disconnect() {
        inspectionAuthorized.value=null
        val email=state.value.email
        update { clear(); putString("epoch",UUID.randomUUID().toString()); putString("status","Not connected") }
        work.cancelAllWorkByTag(TAG).result.get()
        work.cancelAllWorkByTag(REMOVAL_TAG).result.get()
        work.cancelUniqueWork(PERIODIC).result.get()
        try { if(email.isNotEmpty()) auth.signOut(email) } catch(_:Exception) {
            update { putString("error","Disconnected on this device. To revoke Drive permission while offline, use your Google Account permissions page when online.") }
        }
    }
    fun automatic(enabled:Boolean) {
        check(state.value.email.isNotEmpty())
        if(enabled) requireBackupUnlocked()
        check(!enabled || !cloudBarrier) { "Use Back Up Now to intentionally create a new cloud backup after purge completes." }
        update { putBoolean("automatic",enabled); if(enabled) putInt("backupDataVersion",2); putString("error","") }
        if(enabled) { schedulePeriodic(); enqueueAutomatic() } else {
            work.cancelUniqueWork(PERIODIC); work.cancelAllWorkByTag(AUTO_TAG)
        }
        scope.launch { refresh() }
    }
    fun wifiOnly(enabled:Boolean) {
        update { putBoolean("wifi",enabled) }
        scope.launch {
            val expected=epoch()
            val pendingManual=work.getWorkInfosByTag(TAG).get().any { AUTO_TAG !in it.tags && it.state in listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.BLOCKED) }
            work.cancelAllWorkByTag(AUTO_TAG).result.get()
            if(pendingManual) work.cancelUniqueWork("folio-drive-operation").result.get()
            if(!isCurrent(expected)) return@launch
            if(state.value.automatic) { schedulePeriodic(); enqueueAutomatic() }
            val previous=prefs.getString("operation","").orEmpty()
            if(pendingManual && previous.isNotEmpty() && stored(File(job(previous),"request.json"))) enqueueJob(previous)
        }
    }
    private fun constraints()=Constraints.Builder().setRequiredNetworkType(if(state.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
    private fun schedulePeriodic() {
        work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(15,TimeUnit.MINUTES).setConstraints(constraints()).setInputData(workDataOf("automatic" to true,"epoch" to epoch()))
                .addTag(TAG).addTag(AUTO_TAG).addTag(PERIODIC).build())
    }
    private fun enqueueAutomatic() {
        if(!state.value.backupUnlocked || !state.value.automatic || state.value.email.isBlank() || cloudBarrier) return
        work.enqueueUniqueWork("folio-drive-auto",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints()).setInputData(workDataOf("automatic" to true,"epoch" to epoch()))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).addTag(AUTO_TAG).build())
    }
    private fun epoch()=prefs.getString("epoch","").orEmpty()
    fun cancel() {
        // Pause automatic backup too, so the periodic worker cannot undo the user's cancellation.
        update { putBoolean("automatic",false); putString("status","Backup paused"); putString("error","") }
        work.cancelAllWorkByTag(TAG); work.cancelUniqueWork(PERIODIC)
    }
    suspend fun backUpNow():UUID {
        initializeSafety(); requireBackupUnlocked()
        val email=state.value.email; val receipt=dao.backupRecord(purgeKey(email))
        if(receipt!=null && receipt.blocksBackup()) {
            check(receipt.state=="cloud-purged") { "Finish or resolve cloud deletion before creating another backup." }
            dao.save(receipt.copy(state="cloud-active",session="")); cloudBarrier=false
            update { putString("epoch",UUID.randomUUID().toString()); putString("lastFingerprint",""); putLong("lastBackup",0) }
        }
        update { putInt("backupDataVersion",2) }; return enqueueJob(prepare(false))
    }
    fun restore(preview:RestorePreview):UUID { check(!cloudBarrier) { "Cloud deletion superseded this restore. Find a new backup after deletion is resolved." }; return enqueueJob(prepare(false,preview)) }
    suspend fun retry():UUID {
        check(!cloudBlocked()) { "Cloud deletion superseded interrupted backup jobs." }
        val previous=prefs.getString("operation","").orEmpty()
        if(previous.isNotEmpty() && stored(File(job(previous),"request.json"))) {
            val file=File(job(previous),"request.json"); val request=readJson(file)
            require(request.getString("epoch")==epoch()) { "This operation belongs to a disconnected account." }
            atomicWrite(file,request.put("automatic",false).toString().toByteArray())
            return enqueueJob(previous)
        }
        return backUpNow()
    }
    private fun job(id:String):File { BackupManifest.uuid(id); return File(context.filesDir,"drive-jobs/$id") }
    private fun stored(file:File)=file.exists() || File(file.path+".bak").exists()
    private fun readJson(file:File)=JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8))
    internal fun prepare(automatic:Boolean,preview:RestorePreview?=null):String {
        if(preview==null) requireBackupUnlocked()
        check(state.value.email.isNotEmpty()) { "Connect Google Account first." }
        check(!cloudBarrier) { "Cloud deletion is pending or purged. Explicit Back Up Now is required after completion." }
        val id=UUID.randomUUID().toString(); val dir=job(id).apply { mkdirs() }
        val request=JSONObject().put("epoch",epoch()).put("email",state.value.email).put("automatic",automatic)
            .put("type",if(preview==null) "backup" else "restore").put("remoteId",preview?.remoteId.orEmpty()).put("checksum",preview?.checksum.orEmpty()).put("rootId",preview?.rootId.orEmpty())
        atomicWrite(File(dir,"request.json"),request.toString().toByteArray())
        return id
    }
    private fun enqueueJob(id:String):UUID {
        val request=readJson(File(job(id),"request.json"))
        require(request.getString("epoch")==epoch()) { "This operation belongs to a disconnected account." }
        update { putString("operation",id); putBoolean("recoverable",true); putString("error",""); putString("status","Backup pending") }
        val builder=if(request.getString("type")=="restore") OneTimeWorkRequestBuilder<RestoreWorker>() else OneTimeWorkRequestBuilder<BackupWorker>()
        val task=builder.setConstraints(constraints()).setInputData(workDataOf("operation" to id,"epoch" to epoch()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build()
        work.enqueueUniqueWork("folio-drive-operation",ExistingWorkPolicy.REPLACE,task)
        return task.id
    }
    private fun store(expected:String,email:String,automatic:Boolean,purge:Boolean=false):DriveStore {
        fun guard() {
            if(epoch()!=expected || state.value.email!=email || automatic && (!state.value.automatic || prefs.getInt("backupDataVersion",1)<2)) throw CancellationException("Backup permission changed")
        }
        return GoogleDriveDataSource({ guard(); if(purge) auth.purgeToken(email) else auth.token(email) },::guard,{ auth.invalidate(it) })
    }
    suspend fun retryDeletions() = withContext(Dispatchers.IO) {
        val email=state.value.email; require(email.isNotEmpty()) { "Reconnect the original Google account." }
        val name="folio-drive-delete-$email"
        if(work.getWorkInfosForUniqueWork(name).get().any { it.state==WorkInfo.State.RUNNING }) return@withContext
        dao.driveDeletions().filter { it.key.startsWith("$email:delete:") && it.driveRemovalPending }.forEach { dao.save(it.copy(state="delete-pending")) }
        work.cancelUniqueWork(name).result.get()
        enqueueDeletions()
    }
    fun enqueueDeletions() {
        val email=state.value.email; if(email.isEmpty()) return
        work.enqueueUniqueWork("folio-drive-delete-$email",ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DriveDeletionWorker>().addTag(REMOVAL_TAG).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).setInputData(workDataOf("email" to email)).build())
        work.enqueueUniquePeriodicWork("folio-drive-delete-retry",ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveDeletionWorker>(15,TimeUnit.MINUTES).addTag(REMOVAL_TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
    }
    suspend fun executeDeletions(email:String,provided:DriveStore?=null) = operationLock.withLock { withContext(Dispatchers.IO) {
        if(email!=state.value.email || email.isEmpty()) return@withContext
        val purge=dao.backupRecord(purgeKey(email))
        if(purge!=null && purge.state !in listOf("cloud-purged","cloud-active")) return@withContext
        val remote=provided ?: store(epoch(),email,false); removalsRunning.value=true
        try {
            for(receipt in dao.driveDeletions().filter { it.driveRemovalPending && it.key.startsWith("$email:delete:") }) {
                val folder=File(context.cacheDir,"drive-delete-${receipt.hash}").apply { mkdirs() }
                try {
                    reconcileDriveDeletion(dao,receipt,remote,folder)
                    dao.deleteBackupRecord("$email:delete-error:${receipt.hash}")
                } catch(cancel:CancellationException) { throw cancel }
                catch(error:Exception) {
                    val review=error is DriveOwnershipReview || error is org.json.JSONException || error is IllegalArgumentException && !error.message.orEmpty().startsWith("Drive manifest changed")
                    dao.save((dao.backupRecord(receipt.key) ?: receipt).copy(state=if(review) "delete-review" else "delete-pending"))
                    val reason=removalReason(error)
                    dao.save(BackupRecord("$email:delete-error:${receipt.hash}","",receipt.hash,0,System.currentTimeMillis(),"error",reason))
                    if(error is BackupAuthRequired || error is DriveFailure && error.code==401) update { putString("error","Reconnect Google Account to finish removing deleted backups.") }
                    android.util.Log.w("Folio Drive","Removal ${receipt.hash}: ${error.javaClass.simpleName}")
                    throw error
                } finally { folder.deleteRecursively() }
            }
        } finally { removalsRunning.value=false }
    } }
    suspend fun requestCloudPurge(email:String,confirmation:String) = withContext(Dispatchers.IO) {
        require(validPurgeConfirmation(confirmation)) { "Type exact uppercase DELETE." }
        require(email.isNotEmpty() && email==state.value.email) { "Google account changed. Confirm again for the connected account." }
        val key=purgeKey(email)
        val created=database.withTransaction {
            val existing=dao.backupRecord(key)
            if(existing!=null && existing.state !in listOf("cloud-active","cloud-purged")) false
            else { dao.save(BackupRecord(key,"",UUID.randomUUID().toString(),0,System.currentTimeMillis(),"purge-pending")); true }
        }
        if(created && email==state.value.email) {
            cloudBarrier=true
            update { putBoolean("automatic",false); putBoolean("running",false); putInt("done",0); putInt("total",0); putString("epoch",UUID.randomUUID().toString()); putString("status","Cloud backup deletion pending"); putString("error",""); putBoolean("recoverable",false); putString("operation",""); putString("autoOperation","") }
            work.cancelAllWorkByTag(TAG).result.get(); work.cancelUniqueWork(PERIODIC).result.get()
        }
        enqueuePurge(email)
    }
    private fun enqueuePurge(email:String,force:Boolean=false) {
        if(email.isEmpty() || email!=state.value.email) return
        val name="folio-cloud-purge-$email"
        if(force && work.getWorkInfosForUniqueWork(name).get().any { it.state==WorkInfo.State.RUNNING }) return
        val request=OneTimeWorkRequestBuilder<DriveDeletionWorker>().addTag(REMOVAL_TAG)
            .setInputData(workDataOf("email" to email,"purge" to true)).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        work.enqueueUniqueWork(name,if(force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    suspend fun retryCloudPurge() = withContext(Dispatchers.IO) {
        val email=state.value.email; val receipt=dao.backupRecord(purgeKey(email)) ?: return@withContext
        if(receipt.state in listOf("cloud-purged","cloud-active")) return@withContext
        enqueuePurge(email,true)
    }
    internal suspend fun purgeReceipt(email:String)=dao.backupRecord(purgeKey(email))
    suspend fun purgeDetails():List<BackupRecord> {
        val email=state.value.email; val receipt=dao.backupRecord(purgeKey(email)) ?: return emptyList()
        return dao.backupRecords(purgeItemsPrefix(email,receipt.hash)).filter { it.state in listOf("failed","review","pending") }
    }
    suspend fun executeCloudPurge(email:String,provided:DriveStore?=null):Boolean = operationLock.withLock { withContext(Dispatchers.IO) {
        if(email.isEmpty() || email!=state.value.email) return@withContext false
        val receipt=dao.backupRecord(purgeKey(email)) ?: return@withContext true
        if(receipt.state in listOf("cloud-purged","cloud-active")) return@withContext true
        val expected=epoch(); cloudBarrier=true
        fun guard() { currentCoroutineContextGuard(expected,email,false) }
        val result=purgeCloud(dao,receipt,provided ?: store(expected,email,false,true),::guard)
        if(result.state=="purge-verified") {
            guard()
            database.withTransaction {
                for(record in dao.backupRecords("$email:")) {
                    if(listOf(":asset:",":manifest:",":document:",":association:",":delete-error:").any { it in record.key }) dao.deleteBackupRecord(record.key)
                    else if(record.key.startsWith("$email:delete:") && record.driveRemovalPending) dao.save(record.copy(state="delete-complete",session=""))
                }
                dao.save(result.copy(state="cloud-purged"))
            }
            guard(); updateFor(expected) { putLong("lastBackup",0); putString("lastFingerprint",""); putString("status","Cloud backup purged"); putString("error",""); putBoolean("automatic",false) }
            return@withContext true
        }
        if(result.state=="purge-review") throw DriveOwnershipReview(CloudPurgeProgress.read(result).message)
        if(result.state=="purge-failed") throw DriveFailure(403,"insufficientPermissions")
        false
    } }
    internal suspend fun cleanDeletedStaging() = operationLock.withLock { withContext(Dispatchers.IO) {
        val deleted=dao.driveDeletions(); if(deleted.isEmpty()) return@withContext
        for(folder in File(context.filesDir,"drive-jobs").listFiles().orEmpty()) {
            val request=File(folder,"request.json"); val ready=File(folder,"snapshot.json")
            if(!stored(request) || readJson(request).optString("type")!="backup") continue
            if(!ready.isFile) {
                // No published snapshot: retry will pin the surviving library again.
                for(name in listOf("assets","pins")) check(File(folder,name).deleteRecursively()) { "Could not clean interrupted backup inputs." }
                continue
            }
            val snapshot=BackupManifest.decode(ready.readBytes(),false)
            val kept=snapshot.withoutDeleted(deleted)
            if(kept!=snapshot) atomicWrite(ready,kept.encode())
            val hashes=kept.assets.map { it.hash }.toSet()
            File(folder,"assets").listFiles()?.filter { it.name !in hashes }?.forEach { check(it.delete()) { "Could not clean deleted backup inputs." } }
        }
    } }
    private suspend fun associateBackup(snapshot:BackupManifest,email:String,root:String) = database.withTransaction {
        for(doc in snapshot.documents) {
            val key="$email:document:${doc.id}"; val previous=dao.backupRecord(key)
            if(previous!=null && previous.remoteId!=root) dao.save(previous.copy(key="$email:association:${previous.remoteId}:document:${doc.id}"))
            val hashes=snapshot.pages.filter { it.page.documentId==doc.id }.flatMap { listOf(it.original,it.processed) }.toSet()+listOf(doc.pdfHash).filter { it.isNotEmpty() }
            snapshot.pages.filter { it.page.documentId==doc.id }.forEach { page ->
                val pageKey="$email:page:$root:${page.page.id}"
                val old=dao.backupRecord(pageKey)?.session?.takeIf { it.isNotEmpty() }?.let { org.json.JSONArray(it) }
                val retained=old?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
                dao.save(BackupRecord(pageKey,root,doc.id,0,doc.modifiedAt,"complete",org.json.JSONArray((retained+listOf(page.original,page.processed)).distinct()).toString()))
            }
            val oldHashes=previous?.session?.takeIf { it.isNotEmpty() }?.let { value -> org.json.JSONArray(value).let { array -> (0 until array.length()).map { array.getString(it) } } }.orEmpty()
            dao.save(BackupRecord(key,root,previous?.hash.orEmpty(),0,doc.modifiedAt,"uploading",org.json.JSONArray((oldHashes+hashes).distinct()).toString()))
            if(dao.document(doc.id)?.let { it.deleting } != false) queueDriveDeletion(dao,email,doc.id,root)
            val tombstones=dao.driveDeletions().filter { it.pageRemoval }.map { it.hash }.toSet()
            snapshot.pages.filter { it.page.documentId==doc.id && it.page.id in tombstones }.forEach { queueDrivePageDeletion(dao,email,it.page.id,doc.id,root) }
        }
    }
    suspend fun discover():RestorePreview = withContext(Dispatchers.IO) {
        val email=state.value.email; val remote=store(epoch(),email,false)
        val roots=remote.list(null,"root"); require(roots.isNotEmpty()) { "No Folio backup was found in this account." }
        val (root,latest)=roots.flatMap { root -> remote.list(root.id,"manifest").map { root.id to it } }.maxByOrNull { it.second.createdAt } ?: throw IllegalArgumentException("No completed Folio backup was found.")
        val target=File(context.cacheDir,"drive-manifest-${UUID.randomUUID()}")
        try { remote.download(latest.id,target,BackupManifest.MAX_MANIFEST_BYTES.toLong()); val bytes=target.readBytes(); RestorePreview(latest.id,sha256(bytes),BackupManifest.decode(bytes),root) }
        finally { target.delete() }
    }
    suspend fun execute(id:String,provided:DriveStore?=null,progress:suspend(Int,Int)->Unit = {_,_->}) = operationLock.withLock { withContext(Dispatchers.IO) {
        initializeSafety()
        check(!cloudBlocked()) { "Cloud deletion superseded this backup or restore operation." }
        val dir=job(id); val requestFile=File(dir,"request.json")
        if(!stored(requestFile) && (dao.backupRecord("restore:$id")?.state=="complete" || dao.backupRecord("${state.value.email}:manifest:$id")?.state=="complete")) return@withContext
        val request=readJson(requestFile)
        val expected=request.getString("epoch"); val email=request.getString("email"); val automatic=request.getBoolean("automatic")
        fun guard() { currentCoroutineContextGuard(expected,email,automatic) }
        guard(); if(request.getString("type")=="backup") requireBackupUnlocked()
        val remote=provided ?: store(expected,email,automatic)
        suspend fun report(done:Int,total:Int) { guard(); currentCoroutineContext().ensureActive(); updateFor(expected) { putInt("done",done); putInt("total",total) }; progress(done,total) }
        updateFor(expected) { putString("error",""); putString("status",if(request.getString("type")=="restore") "Restoring…" else "Backing up…"); putBoolean("running",true) }
        if(request.getString("type")=="restore") {
            val manifestFile=File(dir,"restore.json")
            if(!manifestFile.exists()) remote.download(request.getString("remoteId"),manifestFile,BackupManifest.MAX_MANIFEST_BYTES.toLong())
            require(hashFile(manifestFile)==request.getString("checksum")) { "The backup changed after confirmation. Find it again before restoring." }
            val deleted=dao.driveDeletions().filter { (it.key.startsWith("$email:delete:") || it.key.startsWith("local:delete:")) }
            val manifest=BackupManifest.decode(manifestFile.readBytes()).withoutDeleted(deleted); val total=manifest.assets.size+manifest.pages.size+1; var done=0
            report(done,total)
            manifest.assets.forEach { asset ->
                val target=File(dir,asset.hash)
                if(!target.isFile || target.length()!=asset.bytes || hashFile(target)!=asset.hash) remote.download(asset.remoteId,target,asset.bytes)
                require(target.length()==asset.bytes && hashFile(target)==asset.hash) { "Checksum failed for page asset ${asset.hash.take(12)}. Nothing was restored." }
                report(++done,total)
            }
            manifest.pages.forEach { entry ->
                guard(); currentCoroutineContext().ensureActive()
                val prepared=File(dir,"documents/${entry.page.documentId}").apply { mkdirs() }
                suspend fun copy(hash:String,path:String) {
                    val target=File(prepared,path); target.parentFile!!.mkdirs()
                    if(!target.exists() || hashFile(target)!=hash) {
                        val writing=File(target.path+".writing")
                        val ctx=currentCoroutineContext()
                        File(dir,hash).inputStream().use { input -> writing.outputStream().use { out -> val buffer=ByteArray(65536)
                            while(true) { ctx.ensureActive(); guard(); val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) } } }
                        check(writing.renameTo(target)) { "Could not prepare restored image." }
                    }
                }
                copy(entry.original,"originals/${entry.page.id}.source")
                if(entry.original!=entry.processed) copy(entry.processed,"processed/${entry.page.id}.source")
                val bitmap=pipeline.decode(File(dir,entry.processed),768)
                try { val thumb=File(prepared,"thumbnails/${entry.page.id}.jpg").apply { parentFile!!.mkdirs() }; thumb.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)) } }
                finally { bitmap.recycle() }
                report(++done,total)
            }
            manifest.documents.forEach { doc ->
                val prepared=File(dir,"documents/${doc.id}").apply { mkdirs() }
                if(doc.pdfHash.isNotEmpty()) {
                    val target=File(prepared,"pdf/${doc.pdfHash}.pdf").apply { parentFile!!.mkdirs() }
                    File(dir,doc.pdfHash).inputStream().use { input -> target.outputStream().use { input.copyTo(it,65536) } }
                    require(hashFile(target)==doc.pdfHash) { "Restored PDF checksum failed." }
                }
                atomicWrite(File(prepared,"restore-owner"),id.toByteArray())
            }
            guard(); val restored=documents.mergeBackup(manifest,dir,id); report(total,total)
            val root=request.optString("rootId")
            if(root.isNotEmpty()) {
                val mapping=readJson(File(dir,"merge.json")).getJSONObject("documents")
                database.withTransaction { manifest.documents.filter { mapping.getString(it.id)==it.id }.forEach { doc ->
                    val hashes=(manifest.pages.filter { it.page.documentId==doc.id }.flatMap { listOf(it.original,it.processed) }+listOf(doc.pdfHash).filter { it.isNotEmpty() }).distinct()
                    dao.save(BackupRecord("$email:document:${doc.id}",root,"",0,doc.modifiedAt,"complete",org.json.JSONArray(hashes).toString()))
                    manifest.assets.filter { it.hash in hashes }.forEach { asset -> dao.save(BackupRecord("$email:asset:${asset.hash}",asset.remoteId,asset.hash,asset.bytes,doc.modifiedAt,"complete")) }
                } }
            }
            guard(); currentCoroutineContext().ensureActive()
            // mergeBackup's durable Room receipt proves atomic local publication, not just download.
            if(manifest.pages.isNotEmpty() && dao.backupRecord("restore:$id")?.state=="complete") {
                check(safety.edit().putBoolean("unlocked",true).commit()) { "Could not save restored backup safety state." }
            }
            updateFor(expected) { putString("status","Restored $restored documents"); putBoolean("running",false); putBoolean("recoverable",false) }
            dir.deleteRecursively(); refresh()
        } else {
            val ready=File(dir,"snapshot.json")
            if(!ready.exists()) {
                File(dir,"assets").deleteRecursively(); val pins=File(dir,"pins"); pins.deleteRecursively()
                val (origin,pinned)=documents.pinBackup(pins)
                val hashes=linkedMapOf<String,BackupAsset>(); val assetDir=File(dir,"assets").apply { mkdirs() }
                val ctx=currentCoroutineContext()
                val pages=pinned.pages.map { page ->
                    fun retain(path:String):String {
                        val file=File(path); val bytes=file.length(); require(bytes in 1..BackupManifest.MAX_ASSET_BYTES) { "Page asset exceeds the 100 MB backup limit." }
                        val hash=hashFile(file) { ctx.ensureActive(); guard() }
                        val destination=File(assetDir,hash); if(!destination.exists()) check(file.renameTo(destination)) { "Could not stage backup asset." }
                        hashes[hash]=BackupAsset(hash,bytes); return hash
                    }
                    BackupPage(page.copy(originalImageUri="",processedImageUri="",thumbnailUri=""),retain(page.originalImageUri),retain(page.processedImageUri))
                }
                pinned.documents.filter { it.pdfHash.isNotEmpty() }.forEach { doc ->
                    val file=File(pins,doc.pdfHash); require(hashFile(file)==doc.pdfHash && file.length()<=BackupManifest.MAX_ASSET_BYTES)
                    val destination=File(assetDir,doc.pdfHash); if(!destination.exists()) check(file.renameTo(destination))
                    hashes[doc.pdfHash]=BackupAsset(doc.pdfHash,destination.length())
                }
                val manifest=BackupManifest(id,System.currentTimeMillis(),pinned.folders,pinned.documents,pages,hashes.values.toList(),pinned.ocr); manifest.validate(false)
                atomicWrite(File(dir,"fingerprint"),BackupPlanner.fingerprint(origin).toByteArray())
                val docHashes=JSONObject(); origin.documents.forEach { doc -> docHashes.put(doc.id,BackupPlanner.documentFingerprint(origin,doc)) }
                atomicWrite(File(dir,"document-hashes.json"),docHashes.toString().toByteArray())
                atomicWrite(ready,manifest.encode()); pins.deleteRecursively()
            }
            val alreadyDeleted=dao.driveDeletions().filter { (it.key.startsWith("$email:delete:") || it.key.startsWith("local:delete:")) }
            val snapshot=BackupManifest.decode(ready.readBytes(),false).withoutDeleted(alreadyDeleted)
            val root=remote.folder(); val total=snapshot.assets.size+1; var done=0; report(done,total)
            associateBackup(snapshot,email,root)
            suspend fun upload(key:String,hash:String,bytes:Long,file:File,role:String,name:String):String {
                guard(); var record=dao.backupRecord(key)
                if(record?.state=="complete" && record.hash==hash && record.session==root && remote.exists(record.remoteId)) return record.remoteId
                val found=remote.list(root,role,hash).firstOrNull()
                if(found!=null) { dao.save(BackupRecord(key,found.id,hash,bytes,System.currentTimeMillis(),"complete",root)); return found.id }
                val ctx=currentCoroutineContext()
                require(file.isFile && file.length()==bytes && hashFile(file) { ctx.ensureActive(); guard() }==hash) {
                    "Prepared backup data is missing or corrupted. Start a new Back Up Now; your local documents are kept."
                }
                val session=record?.takeIf { it.hash==hash && it.state=="uploading" }?.session?.takeIf { it.isNotEmpty() }?.let { runCatching { vault.open(it) }.getOrNull() }
                val remoteId=remote.upload(root,name,role,hash,file,session) { url -> guard(); dao.save(BackupRecord(key,"",hash,bytes,System.currentTimeMillis(),"uploading",vault.seal(url))) }
                guard(); dao.save(BackupRecord(key,remoteId,hash,bytes,System.currentTimeMillis(),"complete",root)); return remoteId
            }
            val assets=snapshot.assets.map { asset ->
                val remoteId=upload("$email:asset:${asset.hash}",asset.hash,asset.bytes,File(dir,"assets/${asset.hash}"),"asset",asset.hash)
                dao.save(BackupRecord("$email:asset:$root:${asset.hash}",remoteId,asset.hash,asset.bytes,System.currentTimeMillis(),"complete",root))
                report(++done,total); asset.copy(remoteId=remoteId)
            }
            val deleted=dao.driveDeletions().filter { (it.key.startsWith("$email:delete:") || it.key.startsWith("local:delete:")) }
            val manifest=snapshot.copy(assets=assets).withoutDeleted(deleted); manifest.validate(); val file=File(dir,"manifest.json"); atomicWrite(file,manifest.encode())
            val prior=manifestGenerations(remote,root,dir).firstOrNull()
            var reused:String?=null
            if(prior!=null) {
                val previous=File(dir,"previous.json")
                remote.download(prior.file.id,previous,BackupManifest.MAX_MANIFEST_BYTES.toLong())
                if(hashFile(previous)==prior.checksum) {
                    val old=BackupManifest.decode(previous.readBytes())
                    if(sameBackupContent(old,manifest) && old.assets.all { remote.exists(it.remoteId) }) {
                        reused=prior.file.id
                        dao.save(BackupRecord("$email:manifest:$id",prior.file.id,prior.checksum,previous.length(),System.currentTimeMillis(),"complete",root))
                    }
                }
                previous.delete()
            }
            val currentManifest=reused ?: upload("$email:manifest:$id",hashFile(file),file.length(),file,"manifest","manifest-${snapshot.createdAt}-$id.json")
            guard()
            val removed=retainManifests(remote,root,currentManifest,dir)
            if(removed.isNotEmpty()) database.withTransaction {
                dao.backupRecords("$email:manifest:").filter { it.remoteId in removed && it.session==root }
                    .forEach { dao.deleteBackupRecord(it.key) }
            }
            guard(); val docHashes=JSONObject(File(dir,"document-hashes.json").readText())
            database.withTransaction { snapshot.documents.forEach { doc ->
                val key="$email:document:${doc.id}"; val existing=dao.backupRecord(key)
                dao.save(BackupRecord(key,root,docHashes.getString(doc.id),0,doc.modifiedAt,"complete",existing?.session.orEmpty()))
            } }
            if(deleted.isNotEmpty()) enqueueDeletions()
            guard()
            updateFor(expected) { putLong("lastBackup",System.currentTimeMillis()); putString("lastFingerprint",File(dir,"fingerprint").readText()); putString("error",""); putBoolean("running",false); putBoolean("recoverable",false); putString("status","Up to date") }
            report(total,total); dir.deleteRecursively(); refresh()
        }
    } }
    fun isCurrent(expected:String)=expected==epoch() && state.value.email.isNotEmpty()
    fun hasPendingChanges()=state.value.pendingChanges()
    private fun currentCoroutineContextGuard(expected:String,email:String,automatic:Boolean) {
        if(epoch()!=expected || state.value.email!=email || automatic && (!state.value.automatic || prefs.getInt("backupDataVersion",1)<2)) throw CancellationException("Backup account or consent changed")
    }
    suspend fun automaticOperation(expected:String):String? {
        initializeSafety()
        if(expected!=epoch() || !state.value.backupUnlocked || !state.value.automatic || prefs.getInt("backupDataVersion",1)<2 || cloudBlocked()) return null
        refresh(); if(!state.value.pendingChanges()) return null
        val previous=prefs.getString("autoOperation","").orEmpty()
        if(previous.isNotEmpty() && stored(File(job(previous),"request.json"))) return previous
        val id=UUID.randomUUID().toString(); val dir=job(id).apply { mkdirs() }
        atomicWrite(File(dir,"request.json"),JSONObject().put("epoch",epoch()).put("email",state.value.email).put("automatic",true).put("type","backup").toString().toByteArray())
        updateFor(expected) { putString("autoOperation",id); putString("operation",id); putBoolean("recoverable",true) }; return id
    }
    @Synchronized fun failure(error:Exception,retrying:Boolean,expected:String?=null) {
        if(expected!=null && !isCurrent(expected)) return
        update { putBoolean("running",false); putString("status",if(retrying) "Backup pending" else "Backup failed"); putString("error",when(error) {
            is BackupAuthRequired -> error.message
            is DriveFailure -> error.message
            is java.io.IOException -> if(retrying) "Waiting for a connection. Folio will retry." else "Backup could not finish. Check connection and device storage, then retry."
            is IllegalArgumentException,is IllegalStateException -> error.message?.take(240) ?: "The backup could not be validated."
            else -> "Backup could not finish. Reconnect Google Account or retry."
        }) }
    }
    @Synchronized fun cancelled(expected:String) { if(isCurrent(expected)) update { putBoolean("running",false); putString("status","Backup paused") } }
    companion object {
        const val REMOVAL_TAG="folio-drive-removal"
        const val TAG="folio-drive"; const val AUTO_TAG="folio-drive-automatic"; const val PERIODIC="folio-drive-periodic"
        fun atomicWrite(file:File,bytes:ByteArray) { file.parentFile!!.mkdirs(); val atomic=AtomicFile(file); val out=atomic.startWrite()
            try { out.write(bytes); atomic.finishWrite(out) } catch(e:Exception) { atomic.failWrite(out); throw e } }
    }
}

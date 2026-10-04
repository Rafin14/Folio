package dev.folio.scanner.backup

import android.content.Context
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException

class DriveDeletionWorker(context:Context,parameters:WorkerParameters):BackupWorker(context,parameters) {
    override suspend fun doWork():Result {
        val repository=EntryPointAccessors.fromApplication(applicationContext,BackupWorkerDependencies::class.java).backups()
        val email=inputData.getString("email") ?: repository.state.value.email
        val purge=inputData.getBoolean("purge",false)
        if(email.isEmpty() || email!=repository.state.value.email || !purge && repository.pendingDeletions(email)==0) return Result.success()
        return try {
            setForeground(getForegroundInfo())
            if(purge) { if(repository.executeCloudPurge(email)) Result.success() else Result.retry() }
            else { repository.executeDeletions(email); Result.success() }
        }
        catch(cancel:CancellationException) { throw cancel }
        catch(error:Exception) {
            android.util.Log.w("Folio Drive","Removal worker attempt $runAttemptCount: ${error.javaClass.simpleName}")
            if(error is DriveOwnershipReview || error is IllegalArgumentException || error is org.json.JSONException || error is BackupAuthRequired || error is DriveFailure && error.code in listOf(401,403)) Result.failure() else Result.retry()
        }
    }
}

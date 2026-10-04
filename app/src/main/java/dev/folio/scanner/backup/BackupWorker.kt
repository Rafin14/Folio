package dev.folio.scanner.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint @InstallIn(SingletonComponent::class)
interface BackupWorkerDependencies { fun backups():FolioBackupRepository }

open class BackupWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("drive-backup","Google Drive backup",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"drive-backup").setSmallIcon(dev.folio.scanner.R.drawable.ic_folio)
            .setContentTitle(when(this) { is RestoreWorker -> "Restoring Folio documents"; is DriveDeletionWorker -> if(inputData.getBoolean("purge",false)) "Deleting Folio cloud backup" else "Removing deleted Drive backups"; else -> "Backing up Folio documents" })
            .setContentText("Progress is available in Google Drive Backup settings.").setOngoing(true)
            .addAction(0,"Cancel",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(android.os.Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode(),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(),notification)
    }
    override suspend fun doWork():Result {
        val repository=EntryPointAccessors.fromApplication(applicationContext,BackupWorkerDependencies::class.java).backups()
        val epoch=inputData.getString("epoch").orEmpty()
        if(!repository.isCurrent(epoch)) return Result.success()
        return try {
            val automatic=inputData.getBoolean("automatic",false)
            val operation=inputData.getString("operation") ?: if(automatic) repository.automaticOperation(epoch) else null
            if(operation==null) return Result.success()
            setForeground(getForegroundInfo())
            repository.execute(operation) { done,total -> setProgress(workDataOf("done" to done,"total" to total)) }
            if(automatic && repository.hasPendingChanges()) Result.retry() else Result.success()
        } catch(cancel:CancellationException) { repository.cancelled(epoch); throw cancel }
        catch(error:Exception) {
            val retry=runAttemptCount<8 && when(error) {
                is DriveFailure -> BackupPlanner.retryable(error.code,error.reason)
                is java.io.IOException -> true
                else -> false
            }
            repository.failure(error,retry,epoch)
            if(retry) Result.retry() else Result.failure()
        }
    }
}
class RestoreWorker(context:Context,parameters:WorkerParameters):BackupWorker(context,parameters)

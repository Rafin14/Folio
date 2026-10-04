package dev.folio.scanner.data

import android.content.Context
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@EntryPoint @InstallIn(SingletonComponent::class)
interface TrashWorkerDependencies { fun documents(): DocumentRepository; fun backups():dev.folio.scanner.backup.FolioBackupRepository }

class TrashCleanupWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = try {
        val dependencies=EntryPointAccessors.fromApplication(applicationContext,TrashWorkerDependencies::class.java)
        dependencies.documents().cleanExpiredTrash()
        dependencies.backups().cleanDeletedStaging()
        Result.success()
    } catch(cancel:CancellationException) { throw cancel }
    catch(error:Exception) { android.util.Log.w("Folio trash","Cleanup will retry",error); Result.retry() }

    companion object {
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("folio-trash-expiry",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrashCleanupWorker>(1,TimeUnit.DAYS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        }
    }
}

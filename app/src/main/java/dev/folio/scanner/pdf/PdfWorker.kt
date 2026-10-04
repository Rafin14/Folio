package dev.folio.scanner.pdf

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint @InstallIn(SingletonComponent::class)
interface PdfWorkerDependencies { fun pdfs(): PdfRepository; fun utility(): PdfUtility }

class PdfWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("pdf-processing", "PDF processing", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "pdf-processing")
            .setSmallIcon(dev.folio.scanner.R.drawable.ic_folio).setContentTitle("Processing your PDF")
            .setContentText("Folio keeps your source files. Progress is available in PDF workspace.")
            .setOngoing(true).addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }
    override suspend fun doWork(): Result {
        val operation = inputData.getString("operation") ?: return Result.failure()
        val repository = EntryPointAccessors.fromApplication(applicationContext, PdfWorkerDependencies::class.java).pdfs()
        return try {
            setForeground(getForegroundInfo())
            repository.execute(operation) { done, total -> setProgress(workDataOf("done" to done, "total" to total)) }
            repository.cleanCompleted(operation)
            Result.success(workDataOf("operation" to operation))
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            android.util.Log.e("Folio PDF", "PDF operation failed", failure)
            Result.failure(workDataOf("error" to (if (failure is IllegalArgumentException) failure.message else "PDF processing failed. Check the password and available storage, then retry.")))
        } catch (_: OutOfMemoryError) { Result.failure(workDataOf("error" to "Not enough memory. Use fewer pages or Small quality, then retry.")) }
    }
}

/** Same foreground/cancel contract, with device-storage output rather than document assets. */
class PdfUtilityWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun getForegroundInfo():ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pdf-processing","PDF processing",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(applicationContext,"pdf-processing").setSmallIcon(dev.folio.scanner.R.drawable.ic_folio).setContentTitle("Exporting PDF workspace files").setOngoing(true)
            .addAction(0,"Cancel",WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if(android.os.Build.VERSION.SDK_INT>=29) ForegroundInfo(id.hashCode(),notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id.hashCode(),notification)
    }
    override suspend fun doWork():Result {
        val session=inputData.getString("session") ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            EntryPointAccessors.fromApplication(applicationContext,PdfWorkerDependencies::class.java).utility().execute(session) { done,total -> setProgress(workDataOf("done" to done,"total" to total)) }
            Result.success()
        } catch(t:CancellationException) { throw t }
        catch(t:Exception) { Result.failure(workDataOf("error" to (t.message ?: "Export failed. Check storage and retry."))) }
        catch(_:OutOfMemoryError) { Result.failure(workDataOf("error" to "Not enough memory. Choose fewer pages or a smaller quality setting.")) }
    }
}

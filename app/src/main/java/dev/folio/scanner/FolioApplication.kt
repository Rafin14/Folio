package dev.folio.scanner

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FolioApplication : Application() {
    @javax.inject.Inject lateinit var backups: dev.folio.scanner.backup.FolioBackupRepository
    @javax.inject.Inject lateinit var ocr: dev.folio.scanner.ocr.OcrRepository
    override fun onCreate() {
        super.onCreate()
        val process=if(android.os.Build.VERSION.SDK_INT>=28) getProcessName() else java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        if(process!=packageName) return
        backups.start()
        ocr.start()
        dev.folio.scanner.data.TrashCleanupWorker.schedule(this)
        // Remove unencrypted one-page previews left by process termination.
        cacheDir.listFiles()?.filter { (it.name.startsWith("preview-") || it.name.startsWith("print-")) && it.extension == "pdf" }?.forEach { it.delete() }
        // Recipient apps may still be reading a Sharesheet snapshot after Folio closes.
        java.io.File(cacheDir, "shared-images").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }?.forEach { it.deleteRecursively() }
    }
}

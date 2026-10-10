package dev.folio.scanner.pdfanalysis

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

/** Coordinates existing background OCR with private-process layout/OCR inference. */
internal object HeavyInferenceGate {
    private val monitor=Any()
    fun <T> run(context:Context,cancelled:()->Unit={},action:()->T):T=synchronized(monitor) {
        RandomAccessFile(File(context.noBackupFilesDir,"heavy-inference.lock"),"rw").use { file ->
            while(true) {
                cancelled()
                val held=try {file.channel.tryLock()} catch(_:OverlappingFileLockException) {null}
                if(held!=null) return@synchronized held.use {action()}
                Thread.sleep(50)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        }
    }
}

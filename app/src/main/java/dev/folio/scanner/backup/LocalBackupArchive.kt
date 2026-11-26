package dev.folio.scanner.backup

import android.content.Context
import android.net.Uri
import dev.folio.scanner.data.DocumentRepository
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class LocalBackupArchive(
    private val context: Context,
    private val documents: DocumentRepository,
    private val pipeline: ImagePipeline
) {
    suspend fun export(uri: Uri): Int = stage { dir ->
        val (manifest) = documents.stageBackup(dir, dir.name)
        val metadata = manifest.encode()
        val resolver = context.contentResolver
        ZipOutputStream(requireNotNull(resolver.openOutputStream(uri, "wt")) { "Could not open the backup destination." }.buffered()).use { zip ->
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(metadata)
            zip.closeEntry()
            for (asset in manifest.assets.sortedBy { it.hash }) {
                zip.putNextEntry(ZipEntry("assets/${asset.hash}"))
                val written = File(dir, "assets/${asset.hash}").inputStream().use { copyBackupBytes(it, zip, asset.bytes) }
                check(written == asset.bytes) { "A backup asset became incomplete during export." }
                zip.closeEntry()
            }
        }
        manifest.documents.size
    }

    suspend fun restore(uri: Uri): Int = stage { dir ->
        val coroutine = currentCoroutineContext()
        val assets = File(dir, "assets").apply { mkdirs() }
        val resolver = context.contentResolver
        val manifest = ZipInputStream(requireNotNull(resolver.openInputStream(uri)) { "Could not open the backup file." }.buffered()).use { zip ->
            require(zip.nextEntry?.name == "manifest.json") { "Choose a Folio backup ZIP." }
            val metadata = ByteArrayOutputStream()
            copyBackupBytes(zip, metadata, BackupManifest.MAX_MANIFEST_BYTES.toLong())
            val manifest = BackupManifest.decode(metadata.toByteArray(), remote = false)
            val remaining = manifest.assets.associateBy { "assets/${it.hash}" }.toMutableMap()
            while (true) {
                coroutine.ensureActive()
                val entry = zip.nextEntry ?: break
                val asset = requireNotNull(remaining.remove(entry.name)) { "The backup contains an unexpected or duplicate file." }
                val file = File(assets, asset.hash)
                val bytes = file.outputStream().use { copyBackupBytes(zip, it, asset.bytes) }
                require(bytes == asset.bytes && hashFile(file) { coroutine.ensureActive() } == asset.hash) { "Backup asset checksum failed." }
            }
            require(remaining.isEmpty()) { "The backup is missing an expected asset." }
            manifest
        }
        prepareBackupDocuments(manifest, dir, dir.name, pipeline, { File(assets, it) })
        coroutine.ensureActive()
        documents.mergeBackup(manifest, dir, dir.name, remote = false)
    }

    private suspend fun <T> stage(action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "local-backup/${UUID.randomUUID()}")
        active.add(dir.name)
        try {
            check(dir.mkdirs()) { "Could not prepare local backup storage." }
            try { action(dir) }
            finally { withContext(kotlinx.coroutines.NonCancellable) { documents.cleanLocalBackupStage(dir) } }
        } finally { active.remove(dir.name) }
    }

    companion object {
        private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun isActive(id: String) = id in active
    }
}

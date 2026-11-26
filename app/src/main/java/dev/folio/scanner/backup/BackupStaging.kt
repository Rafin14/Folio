package dev.folio.scanner.backup

import android.graphics.Bitmap
import dev.folio.scanner.data.DocumentRepository
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream

internal suspend fun copyBackupBytes(source: InputStream, target: OutputStream, limit: Long, guard: () -> Unit = {}): Long {
    val context = currentCoroutineContext()
    val buffer = ByteArray(65536)
    var total = 0L
    while (true) {
        context.ensureActive()
        guard()
        val read = source.read(buffer)
        if (read < 0) return total
        total += read
        require(total <= limit) { "Backup data exceeds its declared size." }
        target.write(buffer, 0, read)
    }
}

/** Stage the same manifest and immutable assets for either backup destination. */
internal suspend fun DocumentRepository.stageBackup(dir: File, id: String, guard: () -> Unit = {}): Pair<BackupManifest, dev.folio.scanner.data.LibrarySnapshot> {
    val pins = File(dir, "pins")
    val (origin, pinned) = pinBackup(pins)
    val assets = linkedMapOf<String, BackupAsset>()
    val assetDir = File(dir, "assets").apply { mkdirs() }
    val context = currentCoroutineContext()
    fun retain(file: File, expected: String? = null): String {
        val bytes = file.length()
        require(bytes in 1..BackupManifest.MAX_ASSET_BYTES) { "Backup asset exceeds the 100 MB limit or is missing." }
        val hash = hashFile(file) { context.ensureActive(); guard() }
        require(expected == null || expected == hash) { "Stored PDF checksum failed." }
        val destination = File(assetDir, hash)
        if (!destination.exists()) check(file.renameTo(destination)) { "Could not stage backup asset." }
        assets[hash] = BackupAsset(hash, bytes)
        return hash
    }
    val pages = pinned.pages.map { page ->
        BackupPage(page.copy(originalImageUri = "", processedImageUri = "", thumbnailUri = ""),
            retain(File(page.originalImageUri)), retain(File(page.processedImageUri)))
    }
    pinned.documents.filter { it.pdfHash.isNotEmpty() }.distinctBy { it.pdfHash }
        .forEach { retain(File(pins, it.pdfHash), it.pdfHash) }
    val manifest = BackupManifest(id, System.currentTimeMillis(), pinned.folders, pinned.documents,
        pages, assets.values.toList(), pinned.ocr)
    manifest.validate(remote = false)
    pins.deleteRecursively()
    return manifest to origin
}

internal suspend fun prepareBackupDocuments(
    manifest: BackupManifest, stage: File, operation: String, pipeline: ImagePipeline, asset: (String) -> File,
    guard: () -> Unit = {}, pageReady: suspend () -> Unit = {}
) {
    val context = currentCoroutineContext()
    val sizes = manifest.assets.associate { it.hash to it.bytes }
    suspend fun copy(hash: String, target: File) {
        context.ensureActive()
        guard()
        if (target.isFile && target.length() == sizes.getValue(hash) &&
            hashFile(target) { context.ensureActive(); guard() } == hash) return
        target.parentFile!!.mkdirs()
        val partial = File(target.path + ".writing")
        val limit = sizes.getValue(hash)
        val bytes = asset(hash).inputStream().use { source ->
            partial.outputStream().use { copyBackupBytes(source, it, limit, guard) }
        }
        require(bytes == limit && hashFile(partial) { context.ensureActive(); guard() } == hash) { "Prepared backup asset checksum failed." }
        check(partial.renameTo(target)) { "Could not prepare restored asset." }
    }
    for (entry in manifest.pages) {
        val p = entry.page
        val prepared = File(stage, "documents/${p.documentId}")
        copy(entry.original, File(prepared, "originals/${p.id}.source"))
        if (entry.original != entry.processed) copy(entry.processed, File(prepared, "processed/${p.id}.source"))
        val bitmap = pipeline.decode(asset(entry.processed), 768)
        try {
            val thumb = File(prepared, "thumbnails/${p.id}.jpg").apply { parentFile!!.mkdirs() }
            thumb.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { bitmap.recycle() }
        pageReady()
    }
    for (doc in manifest.documents) {
        context.ensureActive()
        guard()
        val prepared = File(stage, "documents/${doc.id}")
        if (doc.pdfHash.isNotEmpty()) copy(doc.pdfHash, File(prepared, "pdf/${doc.pdfHash}.pdf"))
        FolioBackupRepository.atomicWrite(File(prepared, "restore-owner"), operation.toByteArray())
    }
}

package dev.folio.scanner

import android.content.Context
import android.graphics.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.Enhancement
import dev.folio.scanner.processing.Geometry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class EditingTest {
    @Test fun cancellationKeepsPublishedOriginalAndThumbnail() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FolioDatabase::class.java).build()
        val repo = DocumentRepository(database, context)
        val document = repo.create("Interrupted import")
        try {
            val bitmap = Bitmap.createBitmap(2500, 3500, Bitmap.Config.ARGB_8888)
            val bytes = try {
                Canvas(bitmap).drawColor(Color.WHITE)
                ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it); it.toByteArray() }
            } finally { bitmap.recycle() }
            val sawOriginal = CompletableDeferred<Unit>()
            lateinit var importing: Deferred<String>
            val observer = launch(Dispatchers.Default) {
                repo.dao.observePages(document).first { pages -> pages.any { it.processedImageUri == it.originalImageUri } }
                sawOriginal.complete(Unit)
                importing.cancel()
            }
            importing = async(Dispatchers.IO) { repo.importImage(document, ByteArrayInputStream(bytes)) }
            try { importing.await() } catch (_: CancellationException) { }
            withTimeout(15000) { sawOriginal.await(); observer.join() }
            val page = repo.dao.pages(document).single()
            assertTrue(File(page.originalImageUri).exists())
            assertTrue(File(page.processedImageUri).exists())
            assertTrue(File(page.thumbnailUri).exists())
            assertArrayEquals(bytes, File(page.originalImageUri).readBytes())
        } finally { withContext(NonCancellable) { repo.purgeForTest(document); database.close() } }
    }
    @Test fun importEnhanceRotateDuplicateReorderReplaceDeleteAndRecover() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, FolioDatabase::class.java).build()
        val repo = DocumentRepository(database, context)
        val document = repo.create("Editing acceptance")
        try {
            val image = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val bytes = try {
                val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
                canvas.drawText("Local document", 25f, 100f, Paint().apply { color = Color.BLUE; textSize = 24f })
                ByteArrayOutputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
            } finally { image.recycle() }
            val first = repo.importImage(document, ByteArrayInputStream(bytes))
            val original = File(requireNotNull(repo.dao.page(first)).originalImageUri)
            repo.crop(first, Geometry.full) // Manual full-page recovery from LCNet heading false positive.
            repo.edit(first, enhancement = Enhancement("Document", contrast = 1.1, sharpness = .4), rotation = 90)
            val edited = requireNotNull(repo.dao.page(first))
            assertTrue(edited.width > edited.height)
            assertArrayEquals(bytes, original.readBytes())
            val copy = repo.duplicatePage(first)
            assertNotEquals(edited.originalImageUri, repo.dao.page(copy)!!.originalImageUri)
            repo.reorder(document, listOf(copy, first))
            assertEquals(listOf(copy, first), repo.dao.pages(document).map { it.id })
            val recoveryId = UUID.randomUUID().toString()
            val replacement = repo.importImage(document, ByteArrayInputStream(bytes), recoveryId)
            assertEquals(replacement, repo.importImage(document, ByteArrayInputStream(bytes), recoveryId))
            assertEquals(3, repo.dao.pages(document).size)
            repo.replacePage(first, replacement)
            assertEquals(listOf(copy, replacement), repo.dao.pages(document).map { it.id })
            assertTrue(original.exists())
            repo.deletePages(document, setOf(copy))
            assertEquals(0, repo.dao.pages(document).single().position)
            assertEquals(1, repo.dao.document(document)!!.pageCount)
            assertTrue(File(repo.dao.page(replacement)!!.originalImageUri).exists())
        } finally { repo.purgeForTest(document); database.close() }
    }
}

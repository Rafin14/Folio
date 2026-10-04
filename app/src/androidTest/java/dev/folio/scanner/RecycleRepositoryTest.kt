package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.*
import dev.folio.scanner.di.StorageModule
import dev.folio.scanner.processing.Enhancement
import dev.folio.scanner.ui.ThumbnailCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

class RecycleRepositoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun image(): ByteArray {
        val bitmap=Bitmap.createBitmap(800,1200,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        return try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
    }
    @Test fun renameTrashRestartRestoreAndFolderFallbackPreserveOriginals()=runBlocking {
        val name="recycle-${UUID.randomUUID()}"
        fun open()=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
        var db=open(); var repo=DocumentRepository(db,context)
        var id=""
        try {
            repo.createFolder("Original location"); val folder=repo.folders.first().single().id
            id=repo.create("Original title",folder)
            val bytes=image(); val page=repo.importImage(id,ByteArrayInputStream(bytes),detectDocument=false)
            repo.edit(page,enhancement=Enhancement("Grayscale"),rotation=90)
            repo.duplicatePage(page)
            val original=repo.dao.document(id)!!; val pages=repo.dao.pages(id)
            val files=pages.flatMap { listOf(it.originalImageUri,it.processedImageUri,it.thumbnailUri) }.distinct().associateWith { File(it).readBytes() }
            repo.rename(id,"  Renamed  ")
            assertEquals(original.copy(title="Renamed"),repo.dao.document(id))
            try { repo.rename(id,"  "); fail("Accepted empty name") } catch(_: IllegalArgumentException) {}
            assertEquals("Renamed",repo.dao.document(id)!!.title)
            repo.delete(id)
            assertTrue(repo.documents.first().isEmpty()); assertEquals(id,repo.trash.first().single().id)
            assertEquals(pages,repo.dao.pages(id)); files.forEach { (path,data)-> assertArrayEquals(data,File(path).readBytes()) }
            db.close(); db=open(); repo=DocumentRepository(db,context)
            assertEquals(id,repo.trash.first().single().id)
            repo.restore(setOf(id)); assertEquals(original.copy(title="Renamed"),repo.documents.first().single())
            repo.delete(id); repo.deleteFolder(folder); repo.restore(setOf(id))
            assertNull(repo.dao.document(id)!!.folderId); assertEquals(pages,repo.dao.pages(id))
            assertArrayEquals(bytes,File(pages.first().originalImageUri).readBytes())
        } finally { if(id.isNotEmpty()) repo.purgeForTest(id); db.close(); context.deleteDatabase(name) }
    }
    @Test fun batchPurgeProtectsActiveDocumentsAndCleansAssociatedFilesAndCache()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,FolioDatabase::class.java).build(); val repo=DocumentRepository(db,context)
        val active=repo.create("Keep active"); val one=repo.create("Recycle one"); val two=repo.create("Recycle two")
        try {
            val page=repo.importImage(one,ByteArrayInputStream(image()),detectDocument=false)
            val before=repo.dao.page(page)!!
            val shared=File(context.cacheDir,"shared-images/${UUID.randomUUID()}")
            repo.snapshotImages(one,shared)
            val pending=File(context.filesDir,"pending-captures/${one}_${UUID.randomUUID()}.jpg").apply { parentFile!!.mkdirs(); writeText("pending") }
            val job=File(context.filesDir,"pdf-jobs/${UUID.randomUUID()}").apply { mkdirs(); File(this,"document").writeText(one); File(this,"input").writeText("input") }
            repo.delete(one); repo.delete(two)
            try { repo.permanentlyDelete(setOf(one,active)); fail("Purged active document") } catch(_: IllegalArgumentException) {}
            assertFalse(repo.dao.document(one)!!.deleting); assertNotNull(repo.dao.document(active)); assertTrue(File(before.originalImageUri).exists())
            repo.restore(setOf(one,two)); assertEquals(3,repo.documents.first().size)
            repo.delete(one); repo.delete(two); repo.permanentlyDelete(setOf(one,two))
            assertNull(repo.dao.document(one)); assertNull(repo.dao.document(two)); assertTrue(repo.dao.pages(one).isEmpty())
            assertFalse(repo.directory(one).exists()); assertFalse(shared.exists()); assertFalse(pending.exists()); assertFalse(job.exists())
            assertEquals(active,repo.documents.first().single().id)
            val expired=File(context.cacheDir,"shared-images/${UUID.randomUUID()}").apply { mkdirs(); File(this,"x.jpg").writeText("old"); setLastModified(1) }
            val recent=File(context.cacheDir,"shared-images/${UUID.randomUUID()}").apply { mkdirs(); File(this,"x.jpg").writeText("fresh") }
            repo.cleanShareCache(); assertFalse(expired.exists()); assertTrue(recent.exists()); recent.deleteRecursively(); Unit
        } finally { listOf(active,one,two).forEach { repo.purgeForTest(it) }; db.close() }
    }
    @Test fun editedThumbnailsUpgradeOnceAndReuseBoundedCache()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,FolioDatabase::class.java).build(); val repo=DocumentRepository(db,context); val id=repo.create("Thumbnail")
        try {
            val page=repo.importImage(id,ByteArrayInputStream(image()),detectDocument=false)
            repo.edit(page,enhancement=Enhancement("Grayscale"),rotation=90)
            val record=repo.dao.page(page)!!; val file=File(record.thumbnailUri)
            val old=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { old.compress(Bitmap.CompressFormat.JPEG,80,it) } } finally { old.recycle() }
            val path=repo.thumbnail(record); val first=ThumbnailCache.load(path)!!; val modified=file.lastModified()
            assertTrue(first.width<=768 && first.height<=768); assertEquals(record.width.toDouble()/record.height,first.width.toDouble()/first.height,.01)
            val pixel=first.getPixel(first.width/2,first.height/2)
            assertTrue(kotlin.math.abs(Color.red(pixel)-Color.green(pixel))<=3); assertTrue(kotlin.math.abs(Color.green(pixel)-Color.blue(pixel))<=3)
            assertEquals(path,repo.thumbnail(record)); assertEquals(modified,file.lastModified()); assertSame(first,ThumbnailCache.load(path))
            repo.delete(id); assertEquals(path,repo.thumbnail(record)); assertTrue(file.exists())
        } finally { repo.purgeForTest(id); db.close() }
    }
}

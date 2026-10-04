package dev.folio.scanner

import android.graphics.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ScanDraftTest {
    @Test fun durableDraftRetakeDiscardAcceptReplacementAndOriginalRetention() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,FolioDatabase::class.java).build()
        val repo = DocumentRepository(db,context); val doc=repo.create("Draft acceptance")
        val bitmap = Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val bytes = try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
        try {
            val id=repo.stageScan(doc,ByteArrayInputStream(bytes))
            assertTrue(repo.dao.pages(doc).isEmpty()); assertEquals(0,repo.dao.document(doc)!!.pageCount)
            assertFalse(repo.draft(id).detected); assertArrayEquals(bytes,File(repo.draft(id).original).readBytes())
            val corners=listOf(Corner(.1,.1),Corner(.9,.1),Corner(.9,.9),Corner(.1,.9))
            repo.cropDraft(id,corners)
            val restored=DocumentRepository(db,context)
            assertEquals(corners,decodeCorners(restored.draft(id).crop))
            assertEquals(id,restored.drafts(doc).single().id)
            restored.discardDraft(id); assertTrue(restored.drafts(doc).isEmpty()); assertTrue(repo.dao.pages(doc).isEmpty())
            assertFalse(File(context.filesDir,"scan-drafts/$id").exists())
            val accepted=repo.stageScan(doc,ByteArrayInputStream(bytes))
            repo.cropDraft(accepted,corners); repo.acceptDraft(accepted,Enhancement("No Shadow"),90)
            val page=repo.dao.pages(doc).single(); assertEquals(accepted,page.id); assertTrue(page.width > page.height)
            assertArrayEquals(bytes,File(page.originalImageUri).readBytes()); assertTrue(repo.drafts(doc).isEmpty())
            val replacement=repo.stageScan(doc,ByteArrayInputStream(bytes),replacement=accepted)
            repo.discardDraft(replacement); assertEquals(accepted,repo.dao.pages(doc).single().id)
            val replace=repo.stageScan(doc,ByteArrayInputStream(bytes),replacement=accepted)
            repo.acceptDraft(replace,Enhancement(),0)
            assertEquals(replace,repo.dao.pages(doc).single().id); assertTrue(File(page.originalImageUri).exists())
            assertEquals(1,repo.dao.document(doc)!!.pageCount)
        } finally { repo.purgeForTest(doc); db.close() }
    }
}

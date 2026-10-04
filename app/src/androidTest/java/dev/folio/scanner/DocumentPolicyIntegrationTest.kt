package dev.folio.scanner

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.PdfWorkerDependencies
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DocumentPolicyIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun createRenameRestoreAndBulkRestoreConflictsAreAtomicAndNeverSilentlyRenamed()=runBlocking {
        val name="names-${UUID.randomUUID()}"; val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
        val pipeline=ImagePipeline(context); val docs=DocumentRepository(db,context,pipeline)
        suspend fun rejected(action:suspend()->Unit) { try { action(); fail("Accepted conflicting name") } catch(error:IllegalArgumentException) { assertTrue(error.message!!.contains("already exists")) } }
        try {
            val a=docs.create("Physics Notes")
            for(title in listOf("Physics Notes","physics notes","  PHYSICS NOTES  ")) rejected { docs.create(title) }
            val b=docs.create("Other"); rejected { docs.rename(b,"physics notes") }
            docs.rename(a," Physics Notes "); assertEquals("Physics Notes",db.documents().document(a)!!.title)
            docs.delete(a); val c=docs.create("physics notes"); rejected { docs.restore(setOf(a)) }
            assertNotNull(db.documents().document(a)!!.trashedAt)
            docs.restore(setOf(a),mapOf(a to "Physics recovered")); assertEquals("Physics recovered",db.documents().document(a)!!.title)
            docs.delete(a); docs.rename(c,"Physics recovered"); rejected { docs.restore(setOf(a)) }
            docs.delete(c); rejected { docs.restore(setOf(a,c)) }; assertNotNull(db.documents().document(c)!!.trashedAt)
            docs.restore(setOf(a,c),mapOf(c to "Other recovered"))
            assertEquals(2,db.documents().allDocuments().count { it.trashedAt==null && it.id in setOf(a,c) })
        } finally { db.documents().allDocuments().forEach { if(it.trashedAt==null) docs.delete(it.id); docs.permanentlyDelete(setOf(it.id)) }; pipeline.close(); db.close(); context.deleteDatabase(name) }
    }
    @Test fun exactExpiryRestoredItemAndMultiItemDeletionUseTheSameCleanup()=runBlocking {
        val name="expiry-${UUID.randomUUID()}"; val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
        val pipeline=ImagePipeline(context); val docs=DocumentRepository(db,context,pipeline)
        try {
            val now=System.currentTimeMillis(); val expired=docs.create("Expired"); val young=docs.create("Young"); val restored=docs.create("Restored")
            for(id in listOf(expired,young,restored)) docs.delete(id)
            db.documents().save(db.documents().document(expired)!!.copy(trashedAt=now-TRASH_RETENTION_MS))
            db.documents().save(db.documents().document(young)!!.copy(trashedAt=now-TRASH_RETENTION_MS+1))
            db.documents().save(db.documents().document(restored)!!.copy(trashedAt=now-TRASH_RETENTION_MS-1))
            docs.restore(setOf(restored)); docs.cleanExpiredTrash(now); docs.cleanExpiredTrash(now)
            assertNull(db.documents().document(expired)); assertNotNull(db.documents().document(young)); assertNull(db.documents().document(restored)!!.trashedAt)
            docs.delete(restored); docs.permanentlyDelete(setOf(young,restored)); assertTrue(db.documents().allDocuments().isEmpty())
        } finally { db.documents().allDocuments().forEach { if(it.trashedAt==null) docs.delete(it.id); docs.permanentlyDelete(setOf(it.id)) }; pipeline.close(); db.close(); context.deleteDatabase(name) }
    }
    @Test fun actualWorkManagerCleanupDeletesControlledExpiredDocument()=runBlocking {
        val docs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
        val id=docs.create("Worker expiry ${UUID.randomUUID()}")
        try {
            docs.delete(id); docs.dao.save(docs.dao.document(id)!!.copy(trashedAt=System.currentTimeMillis()-TRASH_RETENTION_MS))
            val work=WorkManager.getInstance(context); val request=OneTimeWorkRequestBuilder<TrashCleanupWorker>().build()
            work.enqueue(request).result.get()
            withTimeout(30000) { while(work.getWorkInfoById(request.id).get()?.state?.isFinished!=true) delay(100) }
            assertEquals(WorkInfo.State.SUCCEEDED,work.getWorkInfoById(request.id).get()!!.state)
            assertNull(docs.dao.document(id))
            assertTrue(work.getWorkInfosForUniqueWork("folio-trash-expiry").get().isNotEmpty())
        } finally { docs.dao.document(id)?.let { if(it.trashedAt==null) docs.delete(id); docs.permanentlyDelete(setOf(id)) } }
    }
}

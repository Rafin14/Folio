package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.PdfWorkerDependencies
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.*
import java.security.MessageDigest

/** The host also runs these methods separately across force-stop and emulator reboot. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TrashRestartTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val repo get()=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs().documents
    private val saved get()=context.getSharedPreferences("trash-restart-test",0)
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun aStageRetainedTrash()=runBlocking {
        saved.getString("id",null)?.let { repo.purgeForTest(it) }
        val bitmap=Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() } } finally { bitmap.recycle() }
        val id=repo.create("Restart-owned fixture"); repo.importImage(id,ByteArrayInputStream(bytes),detectDocument=false); repo.delete(id)
        assertTrue(saved.edit().putString("id",id).putString("hash",hash(bytes)).commit())
        assertTrue(repo.trash.first().any { it.id==id }); assertTrue(repo.directory(id).exists())
    }
    @Test fun bRestoreAfterFreshProcessAndPurge()=runBlocking {
        val id=requireNotNull(saved.getString("id",null))
        try {
            assertTrue(repo.trash.first().any { it.id==id }); assertTrue(repo.documents.first().none { it.id==id })
            val page=repo.dao.pages(id).single()
            assertEquals(saved.getString("hash",null),hash(File(page.originalImageUri).readBytes()))
            repo.restore(setOf(id)); assertTrue(repo.documents.first().any { it.id==id }); assertNull(repo.dao.document(id)!!.trashedAt)
        } finally { repo.purgeForTest(id); saved.edit().clear().commit() }
    }
}

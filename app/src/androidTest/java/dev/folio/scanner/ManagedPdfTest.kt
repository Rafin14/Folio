package dev.folio.scanner

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.*
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ManagedPdfTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun interruptedPublicationCleansOnlyUnreferencedFiles()=runBlocking {
        val utility=dagger.hilt.android.EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
        val source=File(context.cacheDir,"journal-${UUID.randomUUID()}.pdf")
        PdfDocument(PdfWriter(source)).use {it.addNewPage()}
        val docs=utility.documents;val id=docs.importPdf(source,"Import recovery ${UUID.randomUUID()}",PdfEngine())
        val stage=File(context.filesDir,"pdf-import/${UUID.randomUUID()}").apply {mkdirs()}
        val protected=File(docs.dao.pages(id).single().originalImageUri)
        val native=File(docs.directory(id),"pdf/${docs.dao.document(id)!!.pdfHash}.pdf")
        val orphan=File(docs.directory(id),"originals/orphan.png").apply {writeText("Interrupted output")}
        try {
            File(stage,"publication.json").writeText(org.json.JSONObject().put("document",id).put("files",org.json.JSONArray(listOf(protected.path,native.path,orphan.path))).toString())
            docs.recoverCaptures()
            assertTrue(protected.exists());assertTrue(native.exists());assertFalse(orphan.exists());assertFalse(stage.exists())
            assertEquals(1,docs.dao.pages(id).size)
        } finally {docs.purgeForTest(id);source.delete();stage.deleteRecursively()}
    }
    @Test fun importPreservesNativeTextAndOverwriteIsAtomicAndIdentitySurvivesRestart()=runBlocking {
        val name="managed-test-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
        val engine=PdfEngine()
        val input=File(context.cacheDir,"managed-${UUID.randomUUID()}.pdf")
        val copy=File(context.cacheDir,"managed-copy-${UUID.randomUUID()}.pdf")
        var id=""
        try {
            PdfDocument(PdfWriter(input)).use { pdf -> repeat(2) { n ->
                val page=pdf.addNewPage(com.itextpdf.kernel.geom.PageSize(if(n==0) 300f else 500f,400f))
                PdfCanvas(page).beginText().setFontAndSize(PdfFontFactory.createFont(StandardFonts.HELVETICA),18f).moveText(20.0,200.0).showText("Native PDF page ${n+1}").endText()
            } }
            var docs=DocumentRepository(db,context,ImagePipeline(context))
            id=docs.importPdf(input,"Managed PDF",engine)
            val original=db.documents().document(id)!!
            assertEquals(2,original.pageCount); assertTrue(original.importedPdf); assertTrue(original.pdfHash.isNotEmpty())
            val originalPaths=db.documents().pages(id).flatMap {listOf(it.originalImageUri,it.thumbnailUri)}
            docs.snapshotPdf(id,copy,engine)
            assertArrayEquals(input.readBytes(),copy.readBytes())
            engine.read(copy).use { assertTrue(PdfTextExtractor.getTextFromPage(it.getPage(2)).contains("page 2")) }
            db.close(); db=Room.databaseBuilder(context,FolioDatabase::class.java,name).build()
            docs=DocumentRepository(db,context,ImagePipeline(context))
            assertEquals(original,db.documents().document(id))
            val utility=PdfUtility(context,engine,docs,ImagePipeline(context))
            val session=utility.openManaged(id)
            try {
                utility.updatePages(session,utility.pages(session).take(1))
                assertEquals(2,db.documents().pages(id).size)
                val duplicate=utility.saveToFolio(session,"Managed PDF new")
                assertNotEquals(id,duplicate); assertEquals(1,db.documents().pages(duplicate).size)
                assertEquals(id,utility.session(session).getString("folioDocument"))
                assertEquals(2,db.documents().pages(id).size)
                docs.delete(duplicate); docs.permanentlyDelete(setOf(duplicate))
                utility.saveToFolio(session,"Managed PDF",true)
                assertEquals(1,db.documents().pages(id).size)
                assertTrue(originalPaths.none {File(it).exists()})
            } finally { utility.discard(session) }
            // A freshly read source is required after an earlier overwrite.
            val current=db.documents().document(id)!!
            try { docs.importPdf(input,"Managed PDF",engine,id,"stale"); fail("Stale overwrite accepted") } catch(_:IllegalArgumentException) {}
            assertEquals(current,db.documents().document(id))
            engine.combine(listOf(PdfSource(input,listOf(2))),copy,"Edited")
            docs.importPdf(copy,"Managed PDF",engine,id,current.pdfHash)
            assertEquals(1,db.documents().pages(id).size)
            assertEquals(original.createdAt,db.documents().document(id)!!.createdAt)
            assertEquals(2,engine.count(input))
        } finally { db.close(); context.deleteDatabase(name); input.delete(); copy.delete(); if(id.isNotEmpty()) File(context.filesDir,"documents/$id").deleteRecursively() }
    }
}

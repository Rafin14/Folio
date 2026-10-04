package dev.folio.scanner

import android.content.Context
import android.graphics.*
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class PdfJobsTest {
    @Test fun multiDocumentSnapshotsAndPageSubsetsRetainSelectionAndDocumentOrder()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val pdfs=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).pdfs()
        val repo=pdfs.documents; val a=repo.create("Ordered subset fixture"); val b=repo.create("Combined order fixture")
        suspend fun add(doc:String,color:Int):String {
            val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { bitmap.recycle() }
            return repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
        }
        suspend fun generate(owner:String,documents:List<String>,selection:Set<String>?=null):dev.folio.scanner.data.PdfAsset {
            val operation=pdfs.prepare(owner,"generate","Order proof",selectedPages=selection,sourceDocuments=documents)
            pdfs.execute(operation) { _,_ -> }; return pdfs.results(operation).single()
        }
        fun verify(pdf:dev.folio.scanner.data.PdfAsset,colors:List<Int>) {
            assertEquals(colors.size,pdf.pageCount)
            android.os.ParcelFileDescriptor.open(File(pdf.path),android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                android.graphics.pdf.PdfRenderer(descriptor).use { renderer -> assertEquals(colors.size,renderer.pageCount)
                    colors.forEachIndexed { index,color -> renderer.openPage(index).use { p ->
                        val image=Bitmap.createBitmap(90,120,Bitmap.Config.ARGB_8888)
                        try { p.render(image,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val actual=image.getPixel(45,60)
                            assertTrue(kotlin.math.abs(Color.red(actual)-Color.red(color))<10)
                            assertTrue(kotlin.math.abs(Color.green(actual)-Color.green(color))<10)
                            assertTrue(kotlin.math.abs(Color.blue(actual)-Color.blue(color))<10)
                        } finally { image.recycle() }
                    } }
                }
            }
        }
        try {
            val red=add(a,Color.RED); val green=add(a,Color.GREEN); val blue=add(a,Color.BLUE); add(b,Color.YELLOW)
            repo.reorder(a,listOf(blue,red,green))
            verify(generate(a,listOf(a),linkedSetOf(red,blue)),listOf(Color.BLUE,Color.RED))
            verify(generate(b,listOf(b,a)),listOf(Color.YELLOW,Color.BLUE,Color.RED,Color.GREEN))
            verify(generate(a,listOf(a)),listOf(Color.BLUE,Color.RED,Color.GREEN))
            val first=generate(a,listOf(a)); val second=generate(b,listOf(b))
            val intent=dev.folio.scanner.ui.pdfShareIntent(context,listOf(first,second))
            assertEquals(android.content.Intent.ACTION_SEND_MULTIPLE,intent.action); assertEquals(2,intent.clipData!!.itemCount)
            assertEquals("application/pdf",intent.type)
        } finally { repo.purgeForTest(a); repo.purgeForTest(b) }
    }
    @Test fun durableJobsManipulateImportExportCancelAndRetryWithoutLosingOriginals() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pdfs = EntryPointAccessors.fromApplication(context, PdfWorkerDependencies::class.java).pdfs()
        val repo = pdfs.documents; val document = repo.create("PDF job acceptance")
        suspend fun finish(operation: String) {
            val info = withTimeout(90000) { pdfs.work.getWorkInfosByTagFlow("operation-$operation").first { list -> list.any { it.state.isFinished } }.last { it.state.isFinished } }
            assertEquals(info.outputData.toString(), WorkInfo.State.SUCCEEDED, info.state)
        }
        try {
            val bitmap = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
            val bytes = try { Canvas(bitmap).drawColor(Color.WHITE); ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() } } finally { bitmap.recycle() }
            val first = repo.importImage(document, ByteArrayInputStream(bytes), detectDocument = false)
            val second = repo.duplicatePage(first); repo.reorder(document, listOf(second, first))
            val generation = pdfs.start(document, "generate", "Protected acceptance", password = "family-safe"); finish(generation)
            val source = pdfs.results(generation).single(); assertEquals(2, source.pageCount)
            assertTrue(source.protected); assertTrue(File(pdfs.folder(generation), "complete").exists()); assertFalse(File(pdfs.folder(generation), "request").exists())
            val edit = pdfs.start(document, "combine", "Edited acceptance", listOf(source.id), "2,1", listOf(90, 0), sourcePassword = "family-safe"); finish(edit)
            val edited = pdfs.results(edit).single()
            pdfs.engine.read(File(edited.path)).use { assertEquals(90, it.getPage(1).rotation); assertEquals(2, it.numberOfPages) }
            val split = pdfs.start(document, "split", "Parts", listOf(edited.id)); finish(split)
            val parts = pdfs.results(split); assertEquals(2, parts.size); assertTrue(parts.all { it.pageCount == 1 })
            val merge = pdfs.start(document, "combine", "Merged acceptance", parts.map { it.id }); finish(merge)
            val merged = pdfs.results(merge).single(); assertEquals(2, merged.pageCount)
            val extraction = pdfs.start(document, "combine", "Extracted", listOf(merged.id), "2"); finish(extraction)
            assertEquals(1, pdfs.results(extraction).single().pageCount)
            val import = pdfs.start(document, "import", "PDF images", listOf(merged.id)); finish(import)
            assertEquals(4, repo.dao.pages(document).size)
            assertArrayEquals(bytes, File(repo.dao.page(first)!!.originalImageUri).readBytes())
            val destination = File(repo.directory(document), "pdfs/export-test.pdf").apply { parentFile!!.mkdirs() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
            val export = pdfs.start(document, "export", "Export acceptance", listOf(merged.id), destination = uri.toString()); finish(export)
            assertArrayEquals(File(merged.path).readBytes(), destination.readBytes())
            assertEquals(2, pdfs.engine.count(destination))
            val importedPdf = pdfs.add(document, uri, "Imported original PDF", "")
            assertArrayEquals(destination.readBytes(), File(pdfs.results(importedPdf).single().path).readBytes())
            val retry = pdfs.prepare(document, "generate", "Retry acceptance", password = "private-secret")
            val encryptedRequest = File(pdfs.folder(retry), "request").readBytes()
            assertFalse(String(encryptedRequest, Charsets.ISO_8859_1).contains("private-secret"))
            try { pdfs.execute(retry) { done, _ -> if (done == 1) throw CancellationException("Simulated interruption after first page") }; fail("Expected interrupted operation") }
            catch (_: CancellationException) { }
            assertTrue(pdfs.results(retry).isEmpty())
            assertFalse(File(pdfs.folder(retry), "outputs").exists())
            // Reconstruct repository state, just as a new process does, then retry retained inputs.
            val restarted = PdfRepository(context, repo, PdfEngine()); restarted.retry(retry)
            val info = withTimeout(90000) { pdfs.work.getWorkInfosByTagFlow("operation-$retry").first { it.any { job -> job.state == WorkInfo.State.SUCCEEDED } } }
            assertTrue(info.any { it.state == WorkInfo.State.SUCCEEDED })
            assertEquals(1, pdfs.results(retry).size)
            val held = pdfs.prepare(document, "generate", "Held job")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val holder = launch(Dispatchers.IO) { pdfs.execute(held) { done, _ -> if (done == 0) { entered.complete(Unit); release.await() } } }
            entered.await()
            val cancelled = pdfs.start(document, "generate", "Cancelled job")
            withTimeout(15000) { pdfs.jobs(document).first { it.any { job -> "operation-$cancelled" in job.tags && job.state == WorkInfo.State.RUNNING } } }
            pdfs.work.cancelUniqueWork("operation-$cancelled").result.get()
            withTimeout(15000) { pdfs.jobs(document).first { it.any { job -> "operation-$cancelled" in job.tags && job.state == WorkInfo.State.CANCELLED } } }
            release.complete(Unit); holder.join(); pdfs.cleanCompleted(held)
            assertTrue(pdfs.results(cancelled).isEmpty())
            restarted.recoverPrepared()
            assertTrue(pdfs.jobs(document).first().filter { "operation-$cancelled" in it.tags }.all { it.state == WorkInfo.State.CANCELLED })
            restarted.retry(cancelled)
            withTimeout(60000) { pdfs.jobs(document).first { it.any { job -> "operation-$cancelled" in job.tags && job.state == WorkInfo.State.SUCCEEDED } } }
            assertEquals(1, pdfs.results(cancelled).size)
            val duplicated = repo.duplicate(document)
            assertEquals(repo.dao.pdfs(document).size, repo.dao.pdfs(duplicated).size)
            repo.purgeForTest(duplicated)
            assertTrue(File(source.path).exists())
        } finally { withContext(NonCancellable) { pdfs.work.cancelAllWorkByTag("pdf-$document").result.get(); repo.purgeForTest(document) } }
    }
}

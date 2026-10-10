package dev.folio.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import com.itextpdf.kernel.pdf.*
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfCancellationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    @Test fun actualPdfOperationsWaitForCleanupAtEarlyAndLateCancellationPoints()=runBlocking {
        val source=File(context.cacheDir,"shared-images/cancel-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        PdfDocument(PdfWriter(source)).use {repeat(4) {_ ->it.addNewPage()}}
        val original=source.readBytes();val sourceUri=FileProvider.getUriForFile(context,"dev.folio.scanner.files",source)
        val image=File(source.parentFile,"cancel-${UUID.randomUUID()}.jpg")
        Bitmap.createBitmap(300,450,Bitmap.Config.ARGB_8888).also {b ->try {image.outputStream().use {b.compress(Bitmap.CompressFormat.JPEG,95,it)}} finally {b.recycle()}}
        val imageUri=FileProvider.getUriForFile(context,"dev.folio.scanner.files",image)
        val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
        val before=utility.documents.dao.allDocuments()
        try {for(kind in listOf("split","merge","raster","images","edit","generate")) for(late in listOf(false,true)) {
            val id=utility.open(if(kind=="generate") "images" else kind,if(kind in listOf("images","generate")) listOf(imageUri,imageUri,imageUri) else if(kind=="merge") listOf(sourceUri,sourceUri) else listOf(sourceUri))
            val target=if(kind in listOf("split","raster")) tree else DocumentsContract.createDocument(context.contentResolver,parent,"application/pdf","cancel-$kind.pdf")!!
            val folder=utility.folder(id,create=false)
            val state=utility.session(id).put("destination",target.toString()).put("tree",kind in listOf("split","raster")).put("title","Cancellation fixture").put("selection","").put("state","pending")
            if(kind=="generate") state.put("kind","generate").put("images",JSONArray(listOf(image.path,image.path,image.path))).put("layouts",JSONArray(List(3) {dev.folio.scanner.data.PageLayout().let {p ->org.json.JSONObject().put("size",p.size).put("fit",p.fit).put("width",p.widthMm).put("height",p.heightMm)}}))
            utility.save(id,state)
            val reached=CompletableDeferred<Unit>();val cleanup=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val operation=launch(Dispatchers.IO) {utility.execute(id) {done,total ->if(!reached.isCompleted && (!late || done>=total-1)) {
                reached.complete(Unit)
                try {awaitCancellation()} finally {withContext(NonCancellable) {cleanup.complete(Unit);release.await()}}
            }}}
            try {
                withTimeout(30000) {reached.await()};operation.cancel();cleanup.await()
                val discard=async(Dispatchers.IO) {utility.cancelAndDiscard(id)}
                withTimeout(10000) {while(utility.session(id).optString("state")!="discarding") delay(10)}
                assertFalse("$kind cleanup is awaited",discard.isCompleted);assertTrue(folder.isDirectory)
                assertTrue(runCatching {utility.analyze(id)}.isFailure)
                release.complete(Unit);withTimeout(30000) {operation.join();discard.await()}
                assertFalse(folder.exists());utility.cancelAndDiscard(id)
                assertArrayEquals(original,source.readBytes());assertEquals(before,utility.documents.dao.allDocuments())
                // A fresh native PDF handle is usable after cancellation.
                PdfiumDocument(source).use {assertEquals(4,it.count)}
            } finally {release.complete(Unit);operation.cancelAndJoin();utility.cancelAndDiscard(id);if(kind !in listOf("split","raster")) DocumentsContract.deleteDocument(context.contentResolver,target)}
        }} finally {source.delete();image.delete()}
    }
    @Test fun cancelledNativeOcrAcknowledgesSafeStopBeforeSessionDeletion()=runBlocking {
        val image=Bitmap.createBitmap(1400,1800,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.WHITE);android.graphics.Canvas(this).drawText("Folio cancellation OCR",80f,200f,android.graphics.Paint().apply {color=android.graphics.Color.BLACK;textSize=64f})}
        val file=File(context.cacheDir,"cancel-ocr-${UUID.randomUUID()}.jpg");try {file.outputStream().use {image.compress(Bitmap.CompressFormat.JPEG,96,it)}} finally {image.recycle()}
        val pdf=File(context.cacheDir,"shared-images/cancel-ocr-${UUID.randomUUID()}.pdf").apply {parentFile!!.mkdirs()}
        utility.engine.generate(listOf(file),pdf,"Cancellation OCR",PdfQuality.ORIGINAL)
        val bytes=pdf.readBytes();val id=utility.open("analysis",listOf(FileProvider.getUriForFile(context,"dev.folio.scanner.files",pdf)))
        val reached=CompletableDeferred<Unit>();val connection=AnalysisConnection(context) {if(it.contains("Full Page OCR")) reached.complete(Unit)}
        try {
            connection.start()
            val operation=launch(Dispatchers.IO) {utility.withSessionResources(id) {try {connection.page(utility.folder(id,create=false),0,true,OcrMode.FULL_PAGE)} finally {connection.closeSafely()}}}
            withTimeout(30000) {reached.await()};operation.cancel()
            withTimeout(60000) {utility.cancelAndDiscard(id);operation.join()}
            assertFalse(utility.folder(id,create=false).exists());assertArrayEquals(bytes,pdf.readBytes())
            // Rebinding/running the next OCR proves old cancellation does not poison its sessions.
            val next=utility.open("analysis",listOf(FileProvider.getUriForFile(context,"dev.folio.scanner.files",pdf)))
            val client=AnalysisConnection(context) {}
            try {client.start();client.page(utility.folder(next,create=false),0,true,OcrMode.FULL_PAGE);assertTrue(File(utility.folder(next,create=false),"full-ocr-0.json").exists())}
            finally {client.closeSafely();utility.cancelAndDiscard(next)}
        } finally {connection.closeSafely();utility.cancelAndDiscard(id);file.delete();pdf.delete()}
    }
}

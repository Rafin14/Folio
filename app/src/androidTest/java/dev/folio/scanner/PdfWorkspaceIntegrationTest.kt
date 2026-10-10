package dev.folio.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.geom.PageSize
import dagger.hilt.android.EntryPointAccessors
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PdfWorkspaceIntegrationTest {
    @org.junit.Before fun permissions() { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val utility=EntryPointAccessors.fromApplication(context,PdfWorkerDependencies::class.java).utility()
    private val resolver=context.contentResolver
    private val tree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","root")
    private val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    private val sessions=mutableListOf<String>(); private val resources=mutableListOf<Uri>()
    private fun create(name:String,mime:String="application/pdf")=DocumentsContract.createDocument(resolver,parent,mime,name)!!.also { resources+=it }
    private fun children(folder:Uri)=resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getDocumentId(folder)),arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)!!.use { c -> buildList { while(c.moveToNext()) add(c.getString(1) to DocumentsContract.buildDocumentUriUsingTree(folder,c.getString(0))) } }
    private fun source(name:String):Uri {
        val uri=create(name)
        resolver.openOutputStream(uri,"w")!!.use { stream -> PdfDocument(PdfWriter(stream)).use { pdf -> val font=PdfFontFactory.createFont(StandardFonts.HELVETICA); repeat(4) { i -> val p=pdf.addNewPage(PageSize(300f+i*20,400f+i*20)); PdfCanvas(p).beginText().setFontAndSize(font,32f).moveText(40.0,200.0).showText("${name.substringBefore(' ')} PAGE ${i+1}").endText() } } }
        return uri
    }
    private fun bytes(uri:Uri)=resolver.openInputStream(uri)!!.use { it.readBytes() }
    private suspend fun open(kind:String,inputs:List<Uri>)=utility.open(kind,inputs).also { sessions+=it }
    private suspend fun direct(id:String,dest:Uri,title:String,selection:String="",isTree:Boolean=false) { utility.save(id,utility.session(id).put("destination",dest.toString()).put("tree",isTree).put("title",title).put("selection",selection).put("state","pending")); utility.execute(id) { _,_ -> } }
    private suspend fun cleanup() { sessions.forEach { runCatching { utility.discard(it) } }; resources.reversed().forEach { runCatching { DocumentsContract.deleteDocument(resolver,it) } } }
    @Test fun concurrentSessionReadsCannotRollBackAtomicPageWrites()=runBlocking {
        val id=open("edit",listOf(source("Atomic session ${UUID.randomUUID()}.pdf")))
        val stop=java.util.concurrent.atomic.AtomicBoolean(false)
        val started=CompletableDeferred<Unit>()
        val reader=async(Dispatchers.IO) {
            started.complete(Unit)
            while(!stop.get()) { assertEquals(4,utility.pages(id).size); yield() }
        }
        try {
            started.await()
            withContext(Dispatchers.IO) {
                val metadata=utility.session(id).put("testPadding","x".repeat(262144))
                repeat(100) { sequence ->
                    utility.save(id,metadata.put("testSequence",sequence))
                    assertEquals("Concurrent reads must preserve every completed write",sequence,utility.session(id).getInt("testSequence"))
                }
                val pages=utility.pages(id)
                utility.updatePages(id,pages.map { it.copy(rotation=90) })
                assertTrue(utility.pages(id).all { it.rotation==90 })
            }
        } finally { stop.set(true);reader.await();cleanup() }
    }
    @Test fun mergeCanStartWithOneThenAppendRemoveAndReorder()=runBlocking {
        val a=source("A ${UUID.randomUUID()}.pdf"); val b=source("B ${UUID.randomUUID()}.pdf"); val c=source("C ${UUID.randomUUID()}.pdf")
        try {
            val id=open("merge",listOf(a)); assertEquals(1,utility.session(id).getJSONArray("order").length())
            utility.addMergeInputs(id,listOf(b)); utility.addMergeInputs(id,listOf(c)); utility.removeMergeInput(id,1)
            utility.save(id,utility.session(id).put("order",org.json.JSONArray(listOf(2,0))))
            val out=create("Incremental merge ${UUID.randomUUID()}.pdf"); direct(id,out,"C A merged")
            val local=File(context.cacheDir,"incremental-merge-proof.pdf"); local.writeBytes(bytes(out))
            try { utility.engine.read(local).use { pdf -> assertEquals(8,pdf.numberOfPages); assertTrue(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1)).startsWith("C PAGE 1")); assertTrue(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(5)).startsWith("A PAGE 1")) } } finally { local.delete() }
        } finally { cleanup() }
    }
    @Test fun selectedFolioPagesSnapshotInExplicitCrossDocumentOrder()=runBlocking {
        val a=utility.documents.create("Ordered A ${UUID.randomUUID()}"); val b=utility.documents.create("Ordered B ${UUID.randomUUID()}")
        suspend fun add(doc:String,color:Int):String {
            val bitmap=Bitmap.createBitmap(300,400,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            val input=java.io.ByteArrayOutputStream(); try { bitmap.compress(Bitmap.CompressFormat.PNG,100,input) } finally { bitmap.recycle() }
            return utility.documents.importImage(doc,java.io.ByteArrayInputStream(input.toByteArray()),detectDocument=false)
        }
        try {
            val red=add(a,Color.RED); val blue=add(a,Color.BLUE); val green=add(b,Color.GREEN)
            val originals=utility.documents.dao.pages(a)+utility.documents.dao.pages(b)
            val docs=utility.documents.dao.allDocuments()
            val id=utility.fromPages(listOf(FolioPageChoice(a,blue),FolioPageChoice(b,green),FolioPageChoice(a,red))); sessions+=id
            val out=create("Ordered generation ${UUID.randomUUID()}.pdf"); direct(id,out,"Ordered")
            PdfRenderer(resolver.openFileDescriptor(out,"r")!!).use { renderer -> assertEquals(3,renderer.pageCount)
                listOf(Color.BLUE,Color.GREEN,Color.RED).forEachIndexed { i,color -> renderer.openPage(i).use { page -> val bitmap=Bitmap.createBitmap(60,80,Bitmap.Config.ARGB_8888); try { page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); val pixel=bitmap.getPixel(30,40); assertTrue(kotlin.math.abs(Color.red(pixel)-Color.red(color))<10); assertTrue(kotlin.math.abs(Color.green(pixel)-Color.green(color))<10); assertTrue(kotlin.math.abs(Color.blue(pixel)-Color.blue(color))<10) } finally { bitmap.recycle() } } }
            }
            assertEquals(docs,utility.documents.dao.allDocuments()); assertEquals(originals,utility.documents.dao.pages(a)+utility.documents.dao.pages(b))
        } finally { cleanup(); utility.documents.purgeForTest(a); utility.documents.purgeForTest(b) }
    }
    @Test fun rememberedFolderRequiresPersistedGrantAndRejectsInvalidResource() {
        assertTrue(utility.rememberDestination("test",tree)); val restarted=PdfUtility(context,utility.engine,utility.documents,utility.pipeline)
        assertEquals(tree,restarted.rememberedDestination("test"))
        val missing=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage","missing")
        context.getSharedPreferences("pdf-utility-destinations",0).edit().putString("test",missing.toString()).commit()
        assertNull(restarted.rememberedDestination("test")); assertFalse(context.getSharedPreferences("pdf-utility-destinations",0).contains("test"))
    }
    @Test fun workManagerPartialFailureRetriesRemainingFilesAfterRepositoryReconstruction()=runBlocking {
        val before=utility.documents.dao.allDocuments(); val src=source("Worker ${UUID.randomUUID()}.pdf")
        try {
            val id=open("split",listOf(src))
            resolver.call(parent,"fault",null,Bundle().apply { putInt("after",1) })
            utility.enqueue(id,tree,true,"Worker","1,2-4")
            var workId=utility.work.getWorkInfosByTag("utility-$id").get().single().id
            suspend fun finished():androidx.work.WorkInfo=withTimeout(90000) {
                while(true) { val info=utility.work.getWorkInfoById(workId).get(); if(info?.state?.isFinished==true) return@withTimeout info; delay(200) }
                error("unreachable")
            }
            assertEquals(androidx.work.WorkInfo.State.FAILED,finished().state)
            val state=utility.session(id); assertEquals(1,state.getJSONArray("completed").length())
            val folder=Uri.parse(state.getString("outputFolder")); resources+=folder
            val first=children(folder).first { it.first.endsWith("Page 1.pdf") }.second; val retained=bytes(first)
            val restarted=PdfUtility(context,utility.engine,utility.documents,utility.pipeline)
            restarted.recover(); assertEquals(1,restarted.session(id).getJSONArray("completed").length())
            val oldIds=utility.work.getWorkInfosByTag("utility-$id").get().map { it.id }.toSet()
            restarted.enqueue(id,tree,true,"Worker","1,2-4")
            workId=utility.work.getWorkInfosByTag("utility-$id").get().first { it.id !in oldIds }.id
            withTimeout(90000) { while(restarted.session(id).optString("state")!="complete") delay(200) }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,finished().state)
            assertArrayEquals(retained,bytes(first)); assertEquals(2,children(folder).size)
            children(folder).forEach { (_,uri) -> PdfRenderer(resolver.openFileDescriptor(uri,"r")!!).use { assertTrue(it.pageCount in 1..3) } }
            assertEquals(before,utility.documents.dao.allDocuments()); assertFalse(id in restarted.pending())
        } finally { cleanup() }
    }
    @Test fun interruptedSplitCanChangeDestinationWithoutReusingOldLedger()=runBlocking {
        val src=source("Retarget ${UUID.randomUUID()}.pdf")
        try {
            val id=open("split",listOf(src))
            resolver.call(parent,"fault",null,Bundle().apply { putInt("after",1) })
            try { direct(id,tree,"Retarget","1,2-4",true); fail("Expected storage interruption") } catch(_:java.io.FileNotFoundException) { }
            val oldFolder=Uri.parse(utility.session(id).getString("outputFolder")); resources+=oldFolder
            val oldFile=children(oldFolder).first { it.first.endsWith("Page 1.pdf") }.second; val retained=bytes(oldFile)
            val newFolder=create("New destination ${UUID.randomUUID()}",DocumentsContract.Document.MIME_TYPE_DIR)
            val newTree=DocumentsContract.buildTreeDocumentUri("dev.folio.scanner.test.storage",DocumentsContract.getDocumentId(newFolder))
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n dev.folio.scanner.test/dev.folio.scanner.StorageGrantActivity -d $newTree").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            utility.enqueue(id,newTree,true,"Retarget","1,2-4")
            val workId=utility.work.getWorkInfosByTag("utility-$id").get().single().id
            val info=withTimeout(90000) {
                while(true) { val current=utility.work.getWorkInfoById(workId).get(); if(current?.state?.isFinished==true) return@withTimeout current; delay(200) }
                error("unreachable")
            }
            assertEquals(androidx.work.WorkInfo.State.SUCCEEDED,info.state)
            val output=Uri.parse(utility.session(id).getString("outputFolder")); assertNotEquals(oldFolder,output)
            assertTrue(output.toString().contains(android.net.Uri.encode(DocumentsContract.getDocumentId(newFolder))))
            assertEquals(2,children(output).size)
            children(output).forEach { (_,uri) -> PdfRenderer(resolver.openFileDescriptor(uri,"r")!!).use { assertTrue(it.pageCount in 1..3) } }
            assertArrayEquals(retained,bytes(oldFile))
        } finally { cleanup() }
    }
    @Test fun splitRangesRasterFoldersAndPartialRetryPreserveLibrary()=runBlocking {
        val before=utility.documents.dao.allDocuments(); val src=source("Research Paper ${UUID.randomUUID()}.pdf"); val original=bytes(src)
        try {
            val id=open("split",listOf(src))
            resolver.call(parent,"fault",null,Bundle().apply { putInt("after",1) })
            try { direct(id,tree,"Split","1,3-4",true); fail("Expected storage interruption") } catch(_:Exception) { }
            val state=utility.session(id); assertEquals(1,state.getJSONArray("completed").length()); val folder=Uri.parse(state.getString("outputFolder")); resources+=folder
            val first=children(folder).first { it.first.endsWith("Page 1.pdf") }.second; val firstBytes=bytes(first)
            val restarted=PdfUtility(context,utility.engine,utility.documents,utility.pipeline); restarted.execute(id) { _,_ -> }
            assertArrayEquals(firstBytes,bytes(first)); val outputs=children(folder); assertEquals(2,outputs.size)
            assertTrue(outputs.any { it.first.endsWith("Pages 3-4.pdf") }); outputs.forEach { (_,u) -> PdfRenderer(resolver.openFileDescriptor(u,"r")!!).use { assertTrue(it.pageCount in 1..2) } }
            val raster=open("raster",listOf(src)); direct(raster,tree,"Images","2,4",true); val imageFolder=Uri.parse(utility.session(raster).getString("outputFolder")); resources+=imageFolder
            val png=children(imageFolder); assertEquals(2,png.size); assertTrue(png.all { it.first.endsWith(".png") }); png.forEach { (_,u) -> val b=bytes(u); assertEquals(0x89,b[0].toInt() and 255); assertNotNull(android.graphics.BitmapFactory.decodeByteArray(b,0,b.size).also { it.recycle() }) }
            assertEquals(before,utility.documents.dao.allDocuments()); assertArrayEquals(original,bytes(src))
        } finally { cleanup() }
    }
    @Test fun editAnnotationsRotationReplacementOrderAndSourceIdentitySurviveRecreation()=runBlocking {
        val before=utility.documents.dao.allDocuments(); val src=source("Editor ${UUID.randomUUID()}.pdf"); val original=bytes(src)
        try {
            val id=open("edit",listOf(src)); val pages=utility.pages(id)
            val replacement=File(utility.folder(id),"replacement.pdf"); utility.engine.generate(listOf(image(utility.folder(id))),replacement,"replacement")
            val changed=pages[2].copy(replacement=replacement.path,notes=listOf(PdfNote("A note",color=Color.BLUE)),ink=listOf(PdfInk(Color.RED,.009f,listOf(InkPoint(.2f,.2f),InkPoint(.7f,.7f)))))
            utility.updatePages(id,listOf(pages[3].rotated(),changed,pages[0]))
            val restarted=PdfUtility(context,utility.engine,utility.documents,utility.pipeline); assertEquals(changed,restarted.pages(id)[1]); assertTrue(restarted.session(id).getBoolean("dirty"))
            val out=create("Edited ${UUID.randomUUID()}.pdf"); direct(id,out,"Edited")
            PdfRenderer(resolver.openFileDescriptor(out,"r")!!).use { assertEquals(3,it.pageCount) }
            val local=File(context.cacheDir,"edited-check.pdf"); local.writeBytes(bytes(out)); val input=File(context.cacheDir,"source-check.pdf"); input.writeBytes(original)
            try { utility.engine.read(local).use { pdf -> assertEquals(90,pdf.getPage(1).rotation); utility.engine.read(input).use { assertArrayEquals(it.getPage(4).contentBytes,pdf.getPage(1).contentBytes) }; val text=com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(2)); assertTrue(text.contains("A note")); assertTrue(pdf.getPage(2).contentBytes.toString(Charsets.ISO_8859_1).contains(" RG")) } } finally { local.delete(); input.delete() }
            assertArrayEquals(original,bytes(src)); assertEquals(before,utility.documents.dao.allDocuments())
        } finally { cleanup() }
    }
    private fun image(dir:File):File { val b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }; return File(dir,"input.jpg").also { f -> try { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { b.recycle() } } }
    @Test fun mergeOrderImageConversionAndFolioGenerationNeverCreateDocuments()=runBlocking {
        val before=utility.documents.dao.allDocuments(); val a=source("A ${UUID.randomUUID()}.pdf"); val b=source("B ${UUID.randomUUID()}.pdf"); var document=""
        try {
            val merge=open("merge",listOf(a,b)); utility.save(merge,utility.session(merge).put("order",org.json.JSONArray(listOf(1,0)))); val out=create("Merged ${UUID.randomUUID()}.pdf"); direct(merge,out,"A B merged"); PdfRenderer(resolver.openFileDescriptor(out,"r")!!).use { assertEquals(8,it.pageCount) }
            val merged=File(context.cacheDir,"merge-order-proof.pdf"); merged.writeBytes(bytes(out))
            try { utility.engine.read(merged).use { pdf -> assertTrue(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(1)).startsWith("B PAGE 1")); assertTrue(com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor.getTextFromPage(pdf.getPage(5)).startsWith("A PAGE 1")) } } finally { merged.delete() }
            val photo=create("Photo ${UUID.randomUUID()}.jpg","image/jpeg"); val image=image(context.cacheDir); resolver.openOutputStream(photo,"w")!!.use { o -> image.inputStream().use { it.copyTo(o) } }; image.delete()
            val images=open("images",listOf(photo)); val imagePdf=create("Image ${UUID.randomUUID()}.pdf"); direct(images,imagePdf,"Photo"); PdfRenderer(resolver.openFileDescriptor(imagePdf,"r")!!).use { assertEquals(1,it.pageCount) }; assertEquals(before,utility.documents.dao.allDocuments())
            document=utility.documents.create("Generate ${UUID.randomUUID()}"); val image2=image(context.cacheDir); image2.inputStream().use { utility.documents.importImage(document,it,detectDocument=false) }; image2.delete(); val pages=utility.documents.dao.pages(document); val originalPage=File(pages.single().originalImageUri).readBytes(); val documentsBefore=utility.documents.dao.allDocuments()
            val gen=utility.fromDocuments(listOf(document)); sessions+=gen; val generated=create("Generated ${UUID.randomUUID()}.pdf"); direct(gen,generated,"Generated")
            assertEquals(documentsBefore,utility.documents.dao.allDocuments()); assertEquals(pages,utility.documents.dao.pages(document)); assertArrayEquals(originalPage,File(pages.single().originalImageUri).readBytes()); assertTrue(utility.documents.dao.pdfs(document).isEmpty()); PdfRenderer(resolver.openFileDescriptor(generated,"r")!!).use { assertEquals(1,it.pageCount) }
        } finally { cleanup(); if(document.isNotEmpty()) utility.documents.purgeForTest(document) }
    }
}

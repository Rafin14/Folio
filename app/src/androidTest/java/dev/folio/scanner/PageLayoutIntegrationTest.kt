package dev.folio.scanner

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.*
import dev.folio.scanner.di.StorageModule
import dev.folio.scanner.pdf.*
import dev.folio.scanner.processing.*
import dev.folio.scanner.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.UUID

class PageLayoutIntegrationTest {
    @Test fun namesPaperReorderRestartPdfQualityAndBackupPreserveGeometry()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="paper-${UUID.randomUUID()}"
        fun open()=Room.databaseBuilder(context,FolioDatabase::class.java,name).addCallback(StorageModule.ocrCallback).build()
        var db=open()
        val pipeline=ImagePipeline(object:DocumentCornerDetector { override fun detect(image:Bitmap):DocumentDetectionResult?=null })
        var repo=DocumentRepository(db,context,pipeline)
        val doc=repo.create("Paper acceptance"); val dir=File(context.cacheDir,name).apply { mkdirs() }
        val bitmap=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes=try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it); it.toByteArray() } } finally { bitmap.recycle() }
        try {
            val id=repo.importImage(doc,ByteArrayInputStream(bytes),detectDocument=false)
            repo.renamePage(id," Newton's Laws ")
            repo.edit(id,layout=PageLayout("Letter"),enqueueOcr=false)
            var p=repo.dao.page(id)!!
            assertEquals("Newton's Laws",p.label()); assertEquals(8.5/11,p.width.toDouble()/p.height,.002)
            assertArrayEquals(bytes,File(p.originalImageUri).readBytes())
            val fitted=BitmapFactory.decodeFile(p.processedImageUri)
            try { assertTrue(Color.red(fitted.getPixel(0,0))>245 && Color.green(fitted.getPixel(0,0))>245); assertTrue(Color.green(fitted.getPixel(fitted.width/2,fitted.height/2))<15) } finally { fitted.recycle() }
            val duplicate=repo.duplicatePage(id); repo.reorder(doc,listOf(duplicate,id))
            assertEquals("Newton's Laws",repo.dao.page(id)!!.label())
            repo.edit(duplicate,layout=PageLayout("Custom","Fill",300.5,200.25),enqueueOcr=false)
            db.close(); db=open(); repo=DocumentRepository(db,context,pipeline); p=repo.dao.page(id)!!
            assertEquals(PageLayout("Custom","Fill",300.5,200.25),repo.dao.page(duplicate)!!.layout())
            assertEquals("Letter",p.pageSize); assertEquals(1,p.position); assertEquals("Newton's Laws",p.pageName)
            val engine=PdfEngine()
            for(quality in PdfQuality.entries) {
                val pdf=File(dir,"${quality.name}.pdf")
                engine.generate(listOf(File(p.processedImageUri)),pdf,"Paper",quality,layouts=listOf(p.layout()))
                engine.read(pdf).use { assertEquals(612f,it.getPage(1).pageSize.width,.01f); assertEquals(792f,it.getPage(1).pageSize.height,.01f) }
                PdfRenderer(ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> renderer.openPage(0).use { assertEquals(612,it.width); assertEquals(792,it.height) } }
            }
            val hashes=linkedMapOf<String,BackupAsset>()
            val entries=repo.dao.pages(doc).map { page ->
                fun asset(path:String):String { val file=File(path); val hash=hashFile(file); hashes[hash]=BackupAsset(hash,file.length(),"remote_1"); return hash }
                BackupPage(page,asset(page.originalImageUri),asset(page.processedImageUri))
            }
            val manifest=BackupManifest(UUID.randomUUID().toString(),1,emptyList(),listOf(repo.dao.document(doc)!!),entries,hashes.values.toList())
            for(size in listOf("A4","A5","Legal","Square","Custom")) {
                val layout=if(size=="Custom") PageLayout(size,"Fit",300.0,200.0) else PageLayout(size)
                val file=File(dir,"$size.pdf"); engine.generate(listOf(File(p.processedImageUri)),file,"Paper sizes",layouts=listOf(layout))
                val dims=layout.dimensions!!
                engine.read(file).use { assertEquals((dims.first*72/25.4).toFloat(),it.getPage(1).pageSize.width,.01f); assertEquals((dims.second*72/25.4).toFloat(),it.getPage(1).pageSize.height,.01f) }
            }
            val jobs=PdfRepository(context,repo,engine)
            val selected=jobs.prepare(doc,"generate","Only selected page",selectedPages=setOf(id))
            try { jobs.execute(selected) { _,_-> }; val asset=jobs.results(selected).single(); assertEquals(1,asset.pageCount); engine.read(File(asset.path)).use { assertEquals(612f,it.getPage(1).pageSize.width,.01f) } } finally { jobs.discard(selected) }
            val restored=BackupManifest.decode(manifest.encode()); assertEquals("Letter",restored.pages.first { it.page.id==id }.page.pageSize); assertEquals("Newton's Laws",restored.pages.first().page.pageName); assertEquals(PageLayout("Custom","Fill",300.5,200.25),restored.pages.first { it.page.id==duplicate }.page.layout())
            repo.renamePage(id,""); assertEquals("Page 2",repo.dao.page(id)!!.label())
            val draft=repo.stageScan(doc,ByteArrayInputStream(bytes),replacement=id); repo.cropDraft(draft,Geometry.full)
            val replacement=repo.acceptDraft(draft,Enhancement(),0)
            assertEquals("Letter",repo.dao.page(replacement)!!.pageSize)
            assertEquals(8.5/11,repo.dao.page(replacement)!!.width.toDouble()/repo.dao.page(replacement)!!.height,.002)
        } finally { repo.purgeForTest(doc); db.close(); context.deleteDatabase(name); pipeline.close(); dir.deleteRecursively() }
    }
    @Test fun fitFillAndExifPixelRegionWarpDoNotStretchPortraitLandscapeOrNarrowPages() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline=ImagePipeline(object:DocumentCornerDetector { override fun detect(image:Bitmap):DocumentDetectionResult?=null })
        val file=File.createTempFile("geometry-",".jpg",context.cacheDir)
        try {
            for((w,h) in listOf(900 to 1200,1200 to 900,210 to 297,850 to 1100,200 to 1000,1000 to 200)) {
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                try {
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) }
                    for(orientation in 1..8) {
                        androidx.exifinterface.media.ExifInterface(file).apply { setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
                        val upright=pipeline.decode(file); val reference=try { pipeline.correct(upright,Geometry.full) } finally { upright.recycle() }
                        val cropped=pipeline.correct(file,Geometry.full)
                        try { assertEquals(reference.width,cropped.width); assertEquals(reference.height,cropped.height) } finally { reference.recycle(); cropped.recycle() }
                    }
                    for(mode in listOf("Fit","Fill")) {
                        val paper=pipeline.pageCanvas(bitmap,PageLayout("A4",mode))
                        try { assertEquals(210.0/297,paper.width.toDouble()/paper.height,.006) } finally { paper.recycle() }
                    }
                } finally { bitmap.recycle() }
            }
        } finally { file.delete(); pipeline.close() }
    }
}

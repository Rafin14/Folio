package dev.folio.scanner

import android.graphics.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.*
import dev.folio.scanner.di.StorageModule
import dev.folio.scanner.ocr.*
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Synthetic stress cases, not a photographic accuracy or physical-device benchmark. */
class OcrBenchmarkTest {
    @Test fun largeOriginalAndSequentialStressCasesUseBoundedInputs()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="ocr-large-${UUID.randomUUID()}"
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).addCallback(StorageModule.ocrCallback).build()
        val pipeline=ImagePipeline(context); val docs=DocumentRepository(db,context,pipeline)
        val engine=OcrEngine(context); val repo=OcrRepository(context,db,docs,pipeline,engine)
        val doc=docs.create(name); val source=File(context.cacheDir,"$name.png")
        try {
            val image=Bitmap.createBitmap(3000,4000,Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(Color.WHITE)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=140f }
                listOf("Newton printed document","Invoice number 12345","Large retained original").forEachIndexed { i,t -> drawText(t,130f,350f+i*240f,p) }
            }
            source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
            val id=source.inputStream().use { docs.importImage(doc,it,detectDocument=false) }
            val page=db.documents().page(id)!!; val original=File(page.originalImageUri)
            val hash=dev.folio.scanner.backup.hashFile(original)
            db.documents().save(OcrResult(id,"",0,status="queued")); assertTrue(repo.execute(id))
            val r=db.documents().ocr(id)!!
            assertEquals("complete",r.status); assertTrue(r.text.contains("Newton",true)); assertEquals(hash,dev.folio.scanner.backup.hashFile(original))
            assertTrue(validOcr(r,page))
            val dimensions=Regex("input=(\\d+)x(\\d+)").find(r.diagnostics)!!.groupValues.drop(1).map { it.toInt() }
            assertTrue(dimensions.max()<=2048); assertTrue(dimensions.min()>1400)
            android.util.Log.d("FolioOCRBenchmark","case=12MP ${r.diagnostics}")
            for(case in listOf("dense-small","two-columns","receipt","shadow-mixed-math")) {
                val bitmap=Bitmap.createBitmap(if(case=="receipt") 640 else 1200,1600,Bitmap.Config.ARGB_8888)
                try {
                    val c=Canvas(bitmap); c.drawColor(Color.WHITE)
                    if(case=="shadow-mixed-math") c.drawRect(0f,0f,600f,1600f,Paint().apply { color=Color.rgb(170,170,170) })
                    val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=if(case=="dense-small") 24f else 36f }
                    val lines=if(case=="dense-small") 32 else 10
                    repeat(lines) { i ->
                        val t=if(case=="shadow-mixed-math" && i%3==0) "F = ma   x² + y² = z²" else "Invoice $i payment due"
                        c.drawText(t,35f,100f+i*(if(lines==32) 42f else 120f),p)
                        if(case=="two-columns") c.drawText("Column $i Newton",640f,100f+i*120f,p)
                    }
                    val m=engine.recognize(bitmap)
                    assertTrue("No regions in $case",m.regions.isNotEmpty())
                    assertTrue(m.regions.all { it.points.size==8 && it.points.all { coordinate -> coordinate in 0.0..1.0 } })
                    android.util.Log.d("FolioOCRBenchmark","case=$case init=${m.initializationMs} pre=${m.preprocessMs} det=${m.detectionMs} rec=${m.recognitionMs} post=${m.postprocessMs} total=${m.totalMs} regions=${m.regions.size} meanConfidence=${m.regions.map { it.confidence }.average()} pssKb=${android.os.Debug.getPss()}")
                } finally { bitmap.recycle() }
            }
        } finally { docs.delete(doc); docs.permanentlyDelete(setOf(doc)); source.delete(); engine.close(); db.close(); context.deleteDatabase(name); pipeline.close() }
    }
}

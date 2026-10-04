package dev.folio.scanner

import android.graphics.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.*
import dev.folio.scanner.di.StorageModule
import dev.folio.scanner.ocr.*
import dev.folio.scanner.processing.ImagePipeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OcrIntegrationTest {
    @Test fun indexedMultipleWordQueriesReplaceAndRejectStaleModelData()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext; val name="fts-test-${UUID.randomUUID()}"
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).addCallback(StorageModule.ocrCallback).build()
        try {
            val doc=dev.folio.scanner.data.Document(UUID.randomUUID().toString(),"Indexed fixture",1,2,pageCount=1)
            val p=Page(UUID.randomUUID().toString(),doc.id,0,"o","p","t",100,100)
            db.documents().save(doc); db.documents().save(p)
            val hash=dev.folio.scanner.backup.sha256("original".toByteArray())
            val r=OcrResult(p.id,"Newton invoice 12345",3,regionsJson(listOf(TextRegion("Newton invoice 12345",listOf(.1,.1,.9,.1,.9,.2,.1,.2),.7))),"complete",ocrRevision(p,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,100,100)
            db.documents().save(r)
            assertEquals(1,db.documents().searchOcr(searchExpression("newt inv")).first().size)
            assertEquals(1,db.documents().searchOcr(searchExpression("NEWTON INVOICE")).first().size)
            assertEquals(0,db.documents().searchOcr(searchExpression("invoice absent")).first().size)
            db.documents().save(r.copy(modelVersion="old")); assertTrue(db.documents().searchOcr(searchExpression("Newton")).first().isEmpty())
            db.documents().save(r.copy(text="Payment due",regions=regionsJson(listOf(TextRegion("Payment due",listOf(.1,.1,.9,.1,.9,.2,.1,.2),.7)))))
            assertTrue(db.documents().searchOcr(searchExpression("Newton")).first().isEmpty()); assertEquals(1,db.documents().searchOcr(searchExpression("pay due")).first().size)
            db.documents().clearOcr(p.id); assertTrue(db.documents().searchOcr(searchExpression("pay")).first().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun suppliedModelsRecognizeOfflineAndPersistSearchableRegions()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="ocr-test-${UUID.randomUUID()}"
        val db=Room.databaseBuilder(context,FolioDatabase::class.java,name).addCallback(StorageModule.ocrCallback).build()
        val pipeline=ImagePipeline(context); val docs=DocumentRepository(db,context,pipeline)
        val engine=OcrEngine(context); val repo=OcrRepository(context,db,docs,pipeline,engine)
        val image=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(image); canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=54f; typeface=Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL) }
        listOf("INVOICE NUMBER 12345","Newton second law","Payment due October","Printed English document").forEachIndexed { i,t -> canvas.drawText(t,70f,150f+i*110f,paint) }
        val source=File(context.cacheDir,"$name.png"); source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        val doc=docs.create(name)
        try {
            val id=source.inputStream().use { docs.importImage(doc,it,detectDocument=false) }
            db.documents().save(OcrResult(id,"",0,status="queued"))
            assertTrue(repo.execute(id)); val result=requireNotNull(db.documents().ocr(id)); val page=requireNotNull(db.documents().page(id))
            assertEquals("complete",result.status); assertTrue(result.text,result.text.contains("Newton",true)); assertTrue(validOcr(result,page))
            assertTrue(parseRegions(result.regions).size>=4)
            val hits=db.documents().searchOcr(searchExpression("newt")).first(); assertEquals(id,hits.single().pageId)
            val firstTime=result.modifiedAt; repo.execute(id); assertEquals(firstTime,db.documents().ocr(id)!!.modifiedAt)
            val original=File(page.originalImageUri); val held=File(original.path+".test-held")
            check(original.renameTo(held))
            try {
                db.documents().save(OcrResult(id,"",0,status="queued"))
                assertFalse("Missing input must request a retry",repo.execute(id))
                assertEquals("queued",db.documents().ocr(id)!!.status)
                repo.failedAfterRetries(id); assertEquals("failed",db.documents().ocr(id)!!.status)
            } finally { check(held.renameTo(original)) }
            assertTrue(repo.execute(id)); assertEquals("complete",db.documents().ocr(id)!!.status)
            File(context.getExternalFilesDir(null),"ocr-benchmark.txt").writeText("device=${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT} ${result.diagnostics}\n")
            docs.delete(doc); assertTrue(db.documents().searchOcr(searchExpression("Newton")).first().isEmpty())
            docs.restore(setOf(doc)); assertEquals(id,db.documents().searchOcr(searchExpression("Newton")).first().single().pageId)
            context.getSharedPreferences("ocr-settings",0).edit().putBoolean("automatic",false).commit()
            docs.edit(id,rotation=90); assertNull(db.documents().ocr(id)); assertTrue(db.documents().searchOcr(searchExpression("Newton")).first().isEmpty())
            repo.clear(); assertTrue(db.documents().allOcr().isEmpty()); assertFalse(OcrWork.enabled(context)); assertTrue(original.isFile)
        } finally {
            context.getSharedPreferences("ocr-settings",0).edit().remove("automatic").commit()
            if(db.documents().document(doc)?.trashedAt==null) docs.delete(doc)
            docs.permanentlyDelete(setOf(doc)); source.delete(); engine.close(); db.close(); context.deleteDatabase(name); pipeline.close()
        }
    }
}

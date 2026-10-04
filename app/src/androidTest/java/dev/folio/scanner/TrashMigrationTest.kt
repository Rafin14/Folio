package dev.folio.scanner

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.FolioDatabase
import dev.folio.scanner.di.StorageModule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TrashMigrationTest {
    @Test fun versionSixUpgradeRetainsPageAssetsAndOcrWithActiveDefaults()=runBlocking {
        val inst=InstrumentationRegistry.getInstrumentation(); val context=inst.targetContext
        val name="page-trash-migration-${UUID.randomUUID()}"; val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema=JSONObject(inst.context.assets.open("dev.folio.scanner.data.FolioDatabase/6.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i); val table=e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",table))
                val indexes=e.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries"); for(i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO documents (id,title,createdAt,modifiedAt,favorite,pageCount,deleting) VALUES ('doc','Preserved',1,2,0,1,0)")
            db.execSQL("INSERT INTO pages (id,documentId,position,originalImageUri,processedImageUri,thumbnailUri,width,height,rotation,crop,enhancement) VALUES ('page','doc',0,'original','edited','thumb',400,600,90,'','Original')")
            db.execSQL("INSERT INTO ocr (pageId,text,modifiedAt) VALUES ('page','Preserved OCR',1)")
            db.version=6
        }
        val room=Room.databaseBuilder(context,FolioDatabase::class.java,name).addMigrations(StorageModule.migration6To7).build()
        try { val p=room.documents().page("page")!!; assertNull(p.trashedAt); assertNull(p.trashPosition); assertEquals("original",p.originalImageUri); assertEquals("Preserved OCR",room.documents().ocr("page")!!.text) }
        finally { room.close(); context.deleteDatabase(name) }
    }

    @Test fun versionTwoUpgradePreservesPagesPdfsAndPersistentTrash()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val context=instrumentation.targetContext
        val name="trash-migration-${UUID.randomUUID()}"; val file=context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema=JSONObject(instrumentation.context.assets.open("dev.folio.scanner.data.FolioDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i); val table=entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
            }
            val setup=schema.getJSONArray("setupQueries"); for(i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version=2
            db.execSQL("INSERT INTO folders VALUES ('folder','Family')")
            db.execSQL("INSERT INTO documents VALUES ('doc','Keep',1,2,'folder',1,1,0)")
            db.execSQL("INSERT INTO pages VALUES ('page','doc',0,'/original.jpg','/processed.jpg','/thumb.jpg',600,800,90,'','Grayscale')")
            db.execSQL("INSERT INTO pdfs VALUES ('pdf','doc','operation','PDF','/pdf.pdf',1,3,0)")
        }
        fun open()=Room.databaseBuilder(context,FolioDatabase::class.java,name).addMigrations(StorageModule.migration2To3, StorageModule.migration3To4, StorageModule.migration4To5, StorageModule.migration5To6, StorageModule.migration6To7).build()
        var room=open()
        try {
            val dao=room.documents(); val original=dao.document("doc")!!
            assertNull(original.trashedAt); assertEquals("folder",original.folderId); assertEquals(1L,original.createdAt); assertEquals(2L,original.modifiedAt)
            dao.save(original.copy(trashedAt=123L)); room.close(); room=open()
            assertTrue(room.documents().observeDocuments().first().isEmpty())
            assertEquals(original.copy(trashedAt=123L),room.documents().observeTrash().first().single())
            assertEquals("/original.jpg",room.documents().pages("doc").single().originalImageUri)
            assertEquals("Grayscale",room.documents().pages("doc").single().enhancement)
            assertEquals("/pdf.pdf",room.documents().pdfs("doc").single().path)
        } finally { room.close(); context.deleteDatabase(name) }
    }
}

package dev.folio.scanner

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.FolioDatabase
import dev.folio.scanner.di.StorageModule
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.UUID

class PdfMigrationTest {
    @Test fun addingPdfStoragePreservesExistingDocumentsPagesFoldersAndText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "pdf-migration-${UUID.randomUUID()}"
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("dev.folio.scanner.data.FolioDatabase/1.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index); val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (i in 0 until indices.length()) db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
            db.version = 1
            with(db) {
            execSQL("INSERT INTO folders VALUES ('folder', 'Family')")
            execSQL("INSERT INTO documents VALUES ('doc', 'Retained document', 1, 2, 'folder', 1, 1, 0)")
            execSQL("INSERT INTO pages VALUES ('page', 'doc', 0, '/original.jpg', '/processed.jpg', '/thumb.jpg', 600, 800, 90, '', 'Original')")
            execSQL("INSERT INTO ocr VALUES ('page', 'Retained text', 2)")
            execSQL("INSERT INTO annotations VALUES ('note', 'page', 'note', 'Retained annotation')")
            }
        }
        val room = Room.databaseBuilder(context, FolioDatabase::class.java, name).addMigrations(StorageModule.migration1To2, StorageModule.migration2To3, StorageModule.migration3To4, StorageModule.migration4To5, StorageModule.migration5To6, StorageModule.migration6To7, StorageModule.migration7To8).build()
        try {
        kotlinx.coroutines.runBlocking { val p=room.documents().pages("doc").single(); assertNull(p.pageName); assertEquals("Original",p.pageSize); assertEquals("Fit",p.pageFit) }
        with(room.openHelper.writableDatabase) {
            query("SELECT d.title, d.favorite, f.name, p.originalImageUri, p.rotation, o.text, a.payload FROM documents d JOIN folders f ON f.id = d.folderId JOIN pages p ON p.documentId = d.id JOIN ocr o ON o.pageId = p.id JOIN annotations a ON a.pageId = p.id").use { row ->
                assertTrue(row.moveToFirst()); assertEquals("Retained document", row.getString(0)); assertEquals(1, row.getInt(1))
                assertEquals("Family", row.getString(2)); assertEquals("/original.jpg", row.getString(3)); assertEquals(90, row.getInt(4))
                assertEquals("Retained text", row.getString(5)); assertEquals("Retained annotation", row.getString(6))
            }
            execSQL("INSERT INTO pdfs VALUES ('pdf', 'doc', 'operation', 'New PDF', '/pdf.pdf', 1, 3, 0)")
            query("SELECT COUNT(*) FROM pdfs").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        }
        } finally { room.close(); context.deleteDatabase(name) }
    }
}

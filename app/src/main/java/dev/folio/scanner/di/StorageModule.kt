package dev.folio.scanner.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.folio.scanner.data.FolioDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton fun driveAuth(auth:dev.folio.scanner.backup.GoogleAuthManager):dev.folio.scanner.backup.DriveAuth = auth
    val migration1To2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pdfs (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, operationId TEXT NOT NULL, title TEXT NOT NULL, path TEXT NOT NULL, pageCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, protected INTEGER NOT NULL, FOREIGN KEY(documentId) REFERENCES documents(id) ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX index_pdfs_documentId ON pdfs(documentId)")
                db.execSQL("CREATE INDEX index_pdfs_operationId ON pdfs(operationId)")
            }
        }
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): FolioDatabase =
        Room.databaseBuilder(context, FolioDatabase::class.java, "folio.db").addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6, migration6To7).addCallback(ocrCallback).build()
    val migration6To7 = object : androidx.room.migration.Migration(6,7) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pages ADD COLUMN trashedAt INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE pages ADD COLUMN trashPosition INTEGER DEFAULT NULL")
            db.execSQL("ALTER TABLE pages ADD COLUMN trashDocumentTitle TEXT DEFAULT NULL")
        }
    }
    val migration5To6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pages ADD COLUMN pageName TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE pages ADD COLUMN pageSize TEXT NOT NULL DEFAULT 'Original'")
            db.execSQL("ALTER TABLE pages ADD COLUMN pageFit TEXT NOT NULL DEFAULT 'Fit'")
            db.execSQL("ALTER TABLE pages ADD COLUMN pageWidthMm REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE pages ADD COLUMN pageHeightMm REAL NOT NULL DEFAULT 0")
        }
    }
    private fun ocrTriggers(db:androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS ocr_search_insert AFTER INSERT ON ocr WHEN new.status='complete' BEGIN INSERT INTO ocr_search(pageId,text) VALUES(new.pageId,new.text); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS ocr_search_delete AFTER DELETE ON ocr BEGIN DELETE FROM ocr_search WHERE pageId=old.pageId; END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS ocr_search_update AFTER UPDATE ON ocr BEGIN DELETE FROM ocr_search WHERE pageId=old.pageId; INSERT INTO ocr_search(pageId,text) SELECT new.pageId,new.text WHERE new.status='complete'; END")
    }
    val ocrCallback=object:androidx.room.RoomDatabase.Callback() { override fun onCreate(db:androidx.sqlite.db.SupportSQLiteDatabase) { ocrTriggers(db) } }
    val migration4To5=object:androidx.room.migration.Migration(4,5) {
        override fun migrate(db:androidx.sqlite.db.SupportSQLiteDatabase) {
            val strings=mapOf("regions" to "''","status" to "'outdated'","revision" to "''","sourceHash" to "''","language" to "'en'","engine" to "''","modelVersion" to "''","preprocessingVersion" to "''","diagnostics" to "''","error" to "''")
            strings.forEach { (name,value) -> db.execSQL("ALTER TABLE ocr ADD COLUMN $name TEXT NOT NULL DEFAULT $value") }
            listOf("width","height","processingTimeMs").forEach { db.execSQL("ALTER TABLE ocr ADD COLUMN $it INTEGER NOT NULL DEFAULT 0") }
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS ocr_search USING FTS4(pageId TEXT NOT NULL, text TEXT NOT NULL, tokenize=unicode61)")
            ocrTriggers(db)
        }
    }
    val migration3To4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS backup_records (`key` TEXT NOT NULL PRIMARY KEY, remoteId TEXT NOT NULL, hash TEXT NOT NULL, bytes INTEGER NOT NULL, modifiedAt INTEGER NOT NULL, state TEXT NOT NULL, session TEXT NOT NULL)")
        }
    }
    val migration2To3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE documents ADD COLUMN trashedAt INTEGER DEFAULT NULL")
        }
    }
}

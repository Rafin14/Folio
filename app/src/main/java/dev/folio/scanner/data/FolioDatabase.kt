package dev.folio.scanner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE deleting = 0 ORDER BY id") suspend fun allDocuments(): List<Document>
    @Query("SELECT * FROM folders ORDER BY id") suspend fun allFolders(): List<Folder>
    @Query("SELECT pages.* FROM pages JOIN documents ON pages.documentId = documents.id WHERE documents.deleting = 0 ORDER BY documentId, position") suspend fun allPages(): List<Page>
    @Query("SELECT * FROM backup_records WHERE `key` = :key") suspend fun backupRecord(key: String): BackupRecord?
    @Upsert suspend fun save(record: BackupRecord)
    @Query("SELECT * FROM backup_records WHERE substr(`key`,1,length(:prefix))=:prefix ORDER BY `key`") suspend fun backupRecords(prefix:String):List<BackupRecord>
    @Query("DELETE FROM backup_records WHERE `key`=:key") suspend fun deleteBackupRecord(key:String)
    @Query("SELECT * FROM backup_records WHERE `key` LIKE '%:delete-error:%'") fun observeRemovalErrors():Flow<List<BackupRecord>>
    @Query("SELECT * FROM backup_records WHERE `key` LIKE '%:cloud-purge'") fun observeCloudPurges():Flow<List<BackupRecord>>
    @Query("SELECT * FROM backup_records WHERE `key` LIKE '%:document:' || :id") suspend fun documentBackups(id: String): List<BackupRecord>
    @Query("SELECT * FROM backup_records WHERE `key` LIKE '%:delete:%'") suspend fun driveDeletions(): List<BackupRecord>
    @Query("SELECT * FROM backup_records WHERE `key` LIKE '%:delete:%' ORDER BY modifiedAt DESC") fun observeDriveDeletions(): Flow<List<BackupRecord>>
    @Query("SELECT * FROM documents WHERE deleting = 0 AND trashedAt IS NULL ORDER BY modifiedAt DESC")
    fun observeDocuments(): Flow<List<Document>>
    @Query("SELECT * FROM documents WHERE deleting = 0 AND trashedAt IS NOT NULL ORDER BY trashedAt DESC")
    fun observeTrash(): Flow<List<Document>>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): Folder?
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    fun observeFolders(): Flow<List<Folder>>
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: String): Document?
    @Query("SELECT * FROM documents WHERE deleting = 1")
    suspend fun pendingDeletes(): List<Document>
    @Query("SELECT * FROM pages WHERE documentId = :id AND trashedAt IS NULL ORDER BY position")
    fun observePages(id: String): Flow<List<Page>>
    @Query("SELECT * FROM pages WHERE documentId = :id AND trashedAt IS NULL ORDER BY position")
    suspend fun pages(id: String): List<Page>
    @Query("SELECT * FROM pages WHERE documentId=:id ORDER BY position") suspend fun storedPages(id:String):List<Page>
    @Query("SELECT pages.* FROM pages JOIN documents ON documents.id=pages.documentId WHERE pages.trashedAt IS NOT NULL AND documents.deleting=0 ORDER BY pages.trashedAt DESC") fun observePageTrash():Flow<List<Page>>
    @Query("SELECT * FROM pages WHERE trashedAt IS NOT NULL") suspend fun trashedPages():List<Page>
    @Query("SELECT * FROM pages WHERE id = :id") suspend fun page(id: String): Page?
    @Upsert suspend fun save(document: Document)
    @Upsert suspend fun save(folder: Folder)
    @Upsert suspend fun save(page: Page)
    @Upsert suspend fun save(ocr: OcrResult)
    @Upsert suspend fun save(annotation: Annotation)
    @Query("SELECT * FROM ocr WHERE pageId = :id") suspend fun ocr(id: String): OcrResult?
    @Query("SELECT * FROM ocr") suspend fun allOcr():List<OcrResult>
    @Query("SELECT ocr.* FROM ocr JOIN pages ON pages.id=ocr.pageId WHERE pages.documentId=:id AND pages.trashedAt IS NULL ORDER BY pages.position") fun observeOcr(id:String):Flow<List<OcrResult>>
    @Query("SELECT pages.* FROM pages JOIN documents ON documents.id=pages.documentId JOIN ocr ON ocr.pageId=pages.id WHERE documents.deleting=0 AND documents.trashedAt IS NULL AND pages.trashedAt IS NULL AND ocr.status IN ('queued','processing')") suspend fun pendingOcr():List<Page>
    @Query("SELECT count(*) FROM ocr JOIN pages ON pages.id=ocr.pageId JOIN documents ON documents.id=pages.documentId WHERE documents.deleting=0 AND documents.trashedAt IS NULL AND pages.trashedAt IS NULL AND ocr.status IN ('queued','processing')") fun observePendingOcrCount():Flow<Int>
    @Query("SELECT pages.* FROM pages JOIN documents ON documents.id=pages.documentId JOIN ocr ON ocr.pageId=pages.id WHERE documents.deleting=0 AND documents.trashedAt IS NULL AND pages.trashedAt IS NULL AND (ocr.status='outdated' OR (ocr.status='complete' AND (ocr.modelVersion!=:model OR ocr.preprocessingVersion!=:pre OR ocr.engine!='PaddleOCR' OR ocr.language!='en')))") suspend fun outdatedOcr(model:String,pre:String):List<Page>
    @Query("SELECT pages.documentId, pages.id AS pageId, documents.title, pages.position, pages.thumbnailUri, pages.pageName, snippet(ocr_search,'','','Ã¢â‚¬Â¦',1,24) AS snippet FROM ocr_search JOIN pages ON pages.id=ocr_search.pageId JOIN documents ON documents.id=pages.documentId JOIN ocr ON ocr.pageId=pages.id WHERE ocr_search.text MATCH :query AND ocr.status='complete' AND ocr.engine='PaddleOCR' AND ocr.language='en' AND ocr.modelVersion=:model AND ocr.preprocessingVersion=:pre AND documents.deleting=0 AND documents.trashedAt IS NULL AND pages.trashedAt IS NULL ORDER BY CASE WHEN instr(lower(ocr.text),lower(:phrase))>0 THEN 0 ELSE 1 END, documents.modifiedAt DESC, pages.position LIMIT 100") fun searchOcr(query:String,phrase:String="",model:String=dev.folio.scanner.ocr.OCR_MODEL,pre:String=dev.folio.scanner.ocr.OCR_PREPROCESS):Flow<List<dev.folio.scanner.ocr.OcrSearchHit>>
    @Query("SELECT * FROM annotations WHERE pageId = :id") suspend fun annotations(id: String): List<Annotation>
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Query("DELETE FROM folders WHERE id = :id") suspend fun deleteFolder(id: String)
    @Query("DELETE FROM pages WHERE id = :id") suspend fun deletePage(id: String)
    @Query("DELETE FROM ocr WHERE pageId = :id") suspend fun clearOcr(id: String)
    @Query("UPDATE pages SET position = :position WHERE id = :id") suspend fun position(id: String, position: Int)
    @Query("SELECT * FROM pdfs WHERE documentId = :id ORDER BY createdAt DESC") fun observePdfs(id: String): Flow<List<PdfAsset>>
    @Query("SELECT * FROM pdfs WHERE documentId = :id") suspend fun pdfs(id: String): List<PdfAsset>
    @Query("SELECT * FROM pdfs WHERE id = :id") suspend fun pdf(id: String): PdfAsset?
    @Query("SELECT * FROM pdfs WHERE operationId = :id") suspend fun operationPdfs(id: String): List<PdfAsset>
    @Upsert suspend fun save(pdf: PdfAsset)
    @Query("DELETE FROM pdfs WHERE id = :id") suspend fun deletePdf(id: String)
}

@Database(entities = [Document::class, Folder::class, Page::class, OcrResult::class, OcrSearchIndex::class, Annotation::class, PdfAsset::class, BackupRecord::class], version = 8, exportSchema = true)
abstract class FolioDatabase : RoomDatabase() { abstract fun documents(): DocumentDao }

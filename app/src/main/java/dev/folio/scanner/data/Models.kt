package dev.folio.scanner.data

import androidx.room.*

data class ScanDraft(val id: String, val documentId: String, val original: String, val crop: String, val replacement: String? = null, val detected: Boolean = false)

@Entity(tableName = "backup_records")
data class BackupRecord(@PrimaryKey val key: String, val remoteId: String, val hash: String,
    val bytes: Long, val modifiedAt: Long, val state: String, val session: String = "")

data class LibrarySnapshot(val folders: List<Folder>, val documents: List<Document>, val pages: List<Page>,val ocr:List<OcrResult> = emptyList())

@Entity(tableName = "folders")
data class Folder(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "documents", foreignKeys = [ForeignKey(
    entity = Folder::class, parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.SET_NULL
)], indices = [Index("folderId")])
data class Document(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val pageCount: Int = 0,
    val deleting: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val trashedAt: Long? = null,
    @ColumnInfo(defaultValue = "''") val pdfHash: String = "",
    @ColumnInfo(defaultValue = "0") val pdfRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val importedPdf: Boolean = false
)

@Entity(tableName = "pages", foreignKeys = [ForeignKey(
    entity = Document::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["documentId", "position"], unique = true)])
data class Page(
    @PrimaryKey val id: String,
    val documentId: String,
    val position: Int,
    val originalImageUri: String,
    val processedImageUri: String,
    val thumbnailUri: String,
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
    val crop: String = "",
    val enhancement: String = "Original",
    @ColumnInfo(defaultValue = "NULL") val pageName: String? = null,
    @ColumnInfo(defaultValue = "'Original'") val pageSize: String = "Original",
    @ColumnInfo(defaultValue = "'Fit'") val pageFit: String = "Fit",
    @ColumnInfo(defaultValue = "0") val pageWidthMm: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val pageHeightMm: Double = 0.0,
    @ColumnInfo(defaultValue = "NULL") val trashedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val trashPosition: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val trashDocumentTitle: String? = null
)

@Entity(tableName = "ocr", foreignKeys = [ForeignKey(
    entity = Page::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE
)])
data class OcrResult(@PrimaryKey val pageId: String, val text: String, val modifiedAt: Long,
    @ColumnInfo(defaultValue="''") val regions:String="[]",
    @ColumnInfo(defaultValue="'outdated'") val status:String="outdated",
    @ColumnInfo(defaultValue="''") val revision:String="",
    @ColumnInfo(defaultValue="''") val sourceHash:String="",
    @ColumnInfo(defaultValue="'en'") val language:String="en",
    @ColumnInfo(defaultValue="''") val engine:String="",
    @ColumnInfo(defaultValue="''") val modelVersion:String="",
    @ColumnInfo(defaultValue="''") val preprocessingVersion:String="",
    @ColumnInfo(defaultValue="0") val width:Int=0,
    @ColumnInfo(defaultValue="0") val height:Int=0,
    @ColumnInfo(defaultValue="0") val processingTimeMs:Long=0,
    @ColumnInfo(defaultValue="''") val diagnostics:String="",
    @ColumnInfo(defaultValue="''") val error:String="")

@Fts4(tokenizer=FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName="ocr_search")
data class OcrSearchIndex(val pageId:String,val text:String)

@Entity(tableName = "annotations", foreignKeys = [ForeignKey(
    entity = Page::class, parentColumns = ["id"], childColumns = ["pageId"], onDelete = ForeignKey.CASCADE
)], indices = [Index("pageId")])
data class Annotation(@PrimaryKey val id: String, val pageId: String, val type: String, val payload: String)

@Entity(tableName = "pdfs", foreignKeys = [ForeignKey(entity = Document::class,
    parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)], indices = [Index("documentId"), Index("operationId")])
data class PdfAsset(@PrimaryKey val id: String, val documentId: String, val operationId: String,
    val title: String, val path: String, val pageCount: Int, val createdAt: Long, val protected: Boolean = false)

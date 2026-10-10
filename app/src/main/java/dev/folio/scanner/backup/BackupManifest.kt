package dev.folio.scanner.backup

import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class BackupAsset(val hash: String, val bytes: Long, val remoteId: String = "")
data class BackupPage(val page: Page, val original: String, val processed: String)
data class BackupManifest(val id: String, val createdAt: Long, val folders: List<Folder>,
    val documents: List<Document>, val pages: List<BackupPage>, val assets: List<BackupAsset>,val ocr:List<OcrResult> = emptyList()) {
    fun withoutDocuments(ids: Set<String>): BackupManifest {
        val keptPages=pages.filter { it.page.documentId !in ids }
        val pageIds=keptPages.map { it.page.id }.toSet()
        val hashes=keptPages.flatMap { listOf(it.original,it.processed) }.toSet()+documents.filter { it.id !in ids }.map { it.pdfHash }.filter { it.isNotEmpty() }
        return copy(documents=documents.filter { it.id !in ids },pages=keptPages,
            assets=assets.filter { it.hash in hashes },ocr=ocr.filter { it.pageId in pageIds })
    }
    fun withoutPages(ids: Set<String>): BackupManifest {
        val kept=pages.filter { it.page.id !in ids }.groupBy { it.page.documentId }
        val ordered=documents.flatMap { doc -> val rows=kept[doc.id].orEmpty(); rows.filter { it.page.trashedAt==null }.sortedBy { it.page.position }.mapIndexed { index,p -> p.copy(page=p.page.copy(position=index)) }+rows.filter { it.page.trashedAt!=null } }
        val pageIds=ordered.map { it.page.id }.toSet()
        val changedDocuments=pages.filter { it.page.id in ids }.map { it.page.documentId }.toSet()
        val keptDocuments=documents.map { if(it.id in changedDocuments) it.copy(pdfHash="",pdfRevision=0) else it }
        val hashes=ordered.flatMap { listOf(it.original,it.processed) }.toSet()+keptDocuments.map { it.pdfHash }.filter { it.isNotEmpty() }
        return copy(documents=keptDocuments.map { it.copy(pageCount=kept[it.id].orEmpty().count { p -> p.page.trashedAt==null }) },pages=ordered,assets=assets.filter { it.hash in hashes },ocr=ocr.filter { it.pageId in pageIds })
    }
    fun withoutDeleted(records:List<BackupRecord>) = withoutDocuments(records.filter { !it.pageRemoval }.map { it.hash }.toSet())
        .withoutPages(records.filter { it.pageRemoval }.map { it.hash }.toSet())
    fun validate(remote: Boolean = true) {
        uuid(id); require(createdAt >= 0) { "Invalid backup timestamp." }
        require(documents.size <= 10000 && pages.size <= 50000 && folders.size <= 10000) { "Backup has too many records." }
        require(folders.map { it.id }.distinct().size == folders.size) { "Duplicate folder." }
        require(documents.map { it.id }.distinct().size == documents.size) { "Duplicate document." }
        require(pages.map { it.page.id }.distinct().size == pages.size) { "Duplicate page." }
        val folderIds=folders.map { it.id }.toSet(); val documentIds=documents.map { it.id }.toSet()
        val grouped=pages.groupBy { it.page.documentId }; val assetHashes=assets.map { it.hash }.toSet()
        folders.forEach { uuid(it.id); validatedTitle(it.name) }
        documents.forEach { doc ->
            uuid(doc.id); validatedTitle(doc.title); require(doc.pdfHash.isEmpty() || doc.pdfHash.matches(Regex("[a-f0-9]{64}")) && doc.pdfHash in assetHashes); require(doc.pdfRevision>=0)
            require(!doc.deleting && doc.createdAt >= 0 && doc.modifiedAt >= 0 && (doc.trashedAt == null || doc.trashedAt >= 0)) { "Invalid document metadata." }
            require(doc.folderId == null || doc.folderId in folderIds) { "Missing folder." }
            require(grouped[doc.id].orEmpty().map { it.page.position }.distinct().size==grouped[doc.id].orEmpty().size) { "Duplicate stored page position." }
            val ordered = grouped[doc.id].orEmpty().filter { it.page.trashedAt==null }.sortedBy { it.page.position }
            require(ordered.size == doc.pageCount && ordered.map { it.page.position } == ordered.indices.toList()) { "Invalid page order in ${doc.title}." }
        }
        require(assets.map { it.hash }.distinct().size == assets.size) { "Duplicate asset." }
        assets.forEach { asset ->
            require(asset.hash.matches(Regex("[a-f0-9]{64}")) && asset.bytes in 1..MAX_ASSET_BYTES) { "Invalid asset descriptor." }
            require(!remote || asset.remoteId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Missing Drive asset identifier." }
        }
        require(assets.sumOf { it.bytes } <= 10L * 1024 * 1024 * 1024) { "Backup exceeds the 10 GB restore limit." }
        val sizes=assets.associate { it.hash to it.bytes }
        require(pages.sumOf { (sizes[it.original] ?: 0)+(sizes[it.processed] ?: 0) } <= 10L*1024*1024*1024) { "Expanded page data exceeds the 10 GB restore limit." }
        pages.forEach { entry ->
            val p = entry.page; uuid(p.id); uuid(p.documentId); p.layout(); require(p.trashedAt==null || (p.trashedAt>=0 && p.trashPosition!=null && p.trashPosition>=0 && p.trashDocumentTitle!=null)); if(p.trashedAt!=null) validatedTitle(requireNotNull(p.trashDocumentTitle)); p.pageName?.let { require(pageName(it)==it) }
            require(p.documentId in documentIds) { "Missing page document." }
            require(p.width in 1..100000 && p.height in 1..100000 && p.rotation in listOf(0, 90, 180, 270)) { "Invalid page dimensions or rotation." }
            if (p.crop.isNotEmpty()) {
                val corners = p.crop.split(';').map { part -> val xy=part.split(','); require(xy.size == 2); Corner(xy[0].toDouble(), xy[1].toDouble()) }
                require(Geometry.valid(corners) && corners.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 }) { "Invalid crop." }
            }
            val e=p.enhancement.split('|')
            require(e.size == 1 || e.size == 6 || e.size == 7) { "Invalid enhancement." }
            if(e.size == 1) Enhancement(e[0]) else Enhancement(e[0],e[1].toDouble(),e[2].toDouble(),e[3].toDouble(),e[4].toDouble(),e[5].toDouble(),e.getOrNull(6)?.toDouble() ?: 0.0)
            require(entry.original in assetHashes && entry.processed in assetHashes) { "Missing page asset." }
        }
        require(assets.map { it.hash }.toSet() == pages.flatMap { listOf(it.original,it.processed) }.toSet()+documents.map { it.pdfHash }.filter { it.isNotEmpty() }) { "Unrelated backup assets." }
        require(ocr.map { it.pageId }.distinct().size==ocr.size)
        ocr.forEach { r ->
            val p=pages.firstOrNull { it.page.id==r.pageId } ?: error("OCR has no page.")
            require(r.status in listOf("complete","queued","processing","failed","outdated") && r.modifiedAt>=0 && r.processingTimeMs>=0 && r.text.length<=2*1024*1024)
            require(r.language.length<=32 && r.engine.length<=64 && r.modelVersion.length<=128 && r.preprocessingVersion.length<=128 && r.regions.length<=4*1024*1024 && r.sourceHash.length<=64 && r.revision.length<=64) { "Invalid OCR metadata." }
            if(r.status=="complete") {
                require(r.sourceHash==p.original && r.revision==dev.folio.scanner.ocr.ocrRevision(p.page,r.sourceHash) && r.width==p.page.width && r.height==p.page.height) { "OCR revision does not match its page." }
                val regions=dev.folio.scanner.ocr.parseRegions(r.regions)
                require(r.text==regions.joinToString("\n") { it.text }) { "OCR text does not match its regions." }
            }
        }
    }
    fun encode(): ByteArray {
        val body=body()
        return canonical(JSONObject().put("sha256", sha256(canonical(body).toByteArray())).put("backup", body)).toByteArray().also {
            require(it.size <= MAX_MANIFEST_BYTES) { "Backup manifest exceeds 8 MB." }
        }
    }
    fun body(): JSONObject = JSONObject().put("schema",5).put("id",id).put("createdAt",createdAt)
        .put("ocr",JSONArray(ocr.sortedBy { it.pageId }.map { dev.folio.scanner.ocr.ocrJson(it) }))
        .put("folders",JSONArray(folders.sortedBy { it.id }.map { JSONObject().put("id",it.id).put("name",it.name) }))
        .put("documents",JSONArray(documents.sortedBy { it.id }.map { d -> JSONObject().put("id",d.id).put("title",d.title)
            .put("createdAt",d.createdAt).put("modifiedAt",d.modifiedAt).put("folderId",d.folderId ?: JSONObject.NULL)
            .put("importedPdf",d.importedPdf).put("pdfHash",d.pdfHash).put("pdfRevision",d.pdfRevision).put("favorite",d.favorite).put("pageCount",d.pageCount).put("trashedAt",d.trashedAt ?: JSONObject.NULL) }))
        .put("pages",JSONArray(pages.sortedWith(compareBy({it.page.documentId},{it.page.position})).map { entry -> val p=entry.page
            JSONObject().put("id",p.id).put("documentId",p.documentId).put("position",p.position).put("width",p.width).put("height",p.height)
                .put("pageName",p.pageName ?: JSONObject.NULL).put("pageSize",p.pageSize).put("pageFit",p.pageFit).put("pageWidthMm",p.pageWidthMm).put("pageHeightMm",p.pageHeightMm)
                .put("trashedAt",p.trashedAt ?: JSONObject.NULL).put("trashPosition",p.trashPosition ?: JSONObject.NULL).put("trashDocumentTitle",p.trashDocumentTitle ?: JSONObject.NULL)
                .put("rotation",p.rotation).put("crop",p.crop).put("enhancement",p.enhancement).put("original",entry.original).put("processed",entry.processed) }))
        .put("assets",JSONArray(assets.sortedBy { it.hash }.map { JSONObject().put("hash",it.hash).put("bytes",it.bytes).put("remoteId",it.remoteId) }))
    companion object {
        const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
        const val MAX_ASSET_BYTES = 100L * 1024 * 1024
        fun decode(bytes: ByteArray, remote: Boolean = true): BackupManifest {
            require(bytes.size <= MAX_MANIFEST_BYTES) { "Backup manifest exceeds 8 MB." }
            val envelope=JSONObject(bytes.toString(Charsets.UTF_8)); val b=envelope.getJSONObject("backup")
            require(envelope.getString("sha256") == sha256(canonical(b).toByteArray())) { "Backup manifest checksum failed." }
            require(b.get("schema").toString() in listOf("1","2","3","4","5")) { "Unsupported backup schema. Update Folio before restoring." }
            fun array(name:String)=b.getJSONArray(name).let { a -> List(a.length()) { a.getJSONObject(it) } }
            val folders=array("folders").map { Folder(it.getString("id"),it.getString("name")) }
            val docs=array("documents").map { Document(it.getString("id"),it.getString("title"),it.getLong("createdAt"),it.getLong("modifiedAt"),
                if(it.isNull("folderId")) null else it.getString("folderId"),it.getBoolean("favorite"),it.getInt("pageCount"),false,
                if(it.isNull("trashedAt")) null else it.getLong("trashedAt"),it.optString("pdfHash"),it.optLong("pdfRevision"),it.optBoolean("importedPdf")) }
            val pages=array("pages").map { BackupPage(Page(it.getString("id"),it.getString("documentId"),it.getInt("position"),"","","",
                it.getInt("width"),it.getInt("height"),it.getInt("rotation"),it.getString("crop"),it.getString("enhancement"),if(it.isNull("pageName")) null else it.getString("pageName"),it.optString("pageSize","Original"),it.optString("pageFit","Fit"),it.optDouble("pageWidthMm",0.0),it.optDouble("pageHeightMm",0.0),if(it.isNull("trashedAt")) null else it.getLong("trashedAt"),if(it.isNull("trashPosition")) null else it.getInt("trashPosition"),if(it.isNull("trashDocumentTitle")) null else it.getString("trashDocumentTitle")),it.getString("original"),it.getString("processed")) }
            val assets=array("assets").map { BackupAsset(it.getString("hash"),it.getLong("bytes"),it.getString("remoteId")) }
            val ocr=if(b.get("schema").toString() in listOf("2","3","4","5")) array("ocr").map { dev.folio.scanner.ocr.readOcr(it) } else emptyList()
            return BackupManifest(b.getString("id"),b.getLong("createdAt"),folders,docs,pages,assets,ocr).also { it.validate(remote) }
        }
        fun uuid(value:String) { require(UUID.fromString(value).toString() == value) { "Invalid backup identifier." } }
    }
}

fun canonical(value: Any?): String = when(value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    is Number -> JSONObject.numberToString(value)
    is Boolean -> value.toString()
    else -> error("Unsupported manifest value")
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun hashFile(file: File, checkCancelled: () -> Unit = {}): String {
    val digest=MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer=ByteArray(65536); while(true) { checkCancelled(); val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

object BackupPlanner {
    fun status(connected:Boolean,running:Boolean,changed:Boolean,automatic:Boolean,failed:Boolean=false):String = when {
        !connected -> "Not connected"
        running -> "Backing up…"
        failed -> "Backup failed"
        !changed -> "Up to date"
        automatic -> "Backup pending"
        else -> "Backup paused"
    }
    fun documentFingerprint(snapshot:LibrarySnapshot,doc:Document):String {
        val pages=snapshot.pages.filter { it.documentId==doc.id }; val ids=pages.map { it.id }.toSet()
        return fingerprint(snapshot.copy(folders=snapshot.folders.filter { it.id==doc.folderId },documents=listOf(doc),pages=pages,ocr=snapshot.ocr.filter { it.pageId in ids }))
    }
    /** Paths change whenever Folio publishes a new immutable edited image. Thumbnails are derived caches. */
    fun fingerprint(snapshot: LibrarySnapshot): String = sha256(buildString {
        snapshot.folders.sortedBy { it.id }.forEach { append(JSONObject.quote(it.id)); append(JSONObject.quote(it.name)) }
        snapshot.documents.sortedBy { it.id }.forEach { append(it.toString()) }
        snapshot.pages.sortedWith(compareBy({it.documentId},{it.position})).forEach { append(it.copy(thumbnailUri="").toString().let { text -> if(it.trashedAt==null) text.replace(", trashedAt=null, trashPosition=null, trashDocumentTitle=null", "") else text }) }
        snapshot.ocr.sortedBy { it.pageId }.forEach { append(dev.folio.scanner.ocr.ocrJson(it).toString()) }
    }.toByteArray())
    fun retryable(code: Int, reason: String = ""): Boolean = code in listOf(408,429,500,502,503,504) || code == 403 && reason in listOf("rateLimitExceeded","userRateLimitExceeded","backendError")
}

package dev.folio.scanner.backup

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DriveFile(val id:String,val name:String,val createdAt:String="",val hash:String="")
data class DriveResource(val id:String,val role:String,val parents:List<String>,val folder:Boolean=false,val ownedByMe:Boolean=true,val version:String="",val etag:String="")
class DriveOwnershipReview(message:String):IllegalArgumentException(message)
class DriveFailure(val code:Int,val reason:String="") : IOException(when {
    code==401 -> "Reconnect Google Account."
    reason=="storageQuotaExceeded" -> "Google Drive storage is full. Free space and retry."
    reason in listOf("dailyLimitExceeded","quotaExceeded") -> "Google Drive quota has been reached. Try again later."
    BackupPlanner.retryable(code,reason) -> "Drive is temporarily unavailable. Backup will retry."
    code==404 -> "A required backup file is missing from Drive."
    else -> "Drive could not complete this operation. Check account permissions and retry."
})

/** Two implementations: HTTPS Drive and the credential-free integration-test store. */
interface DriveStore {
    suspend fun list(parent:String?,role:String,hash:String?=null,includeTrashed:Boolean=false):List<DriveFile>
    suspend fun folder():String
    suspend fun exists(id:String):Boolean
    suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String
    suspend fun download(id:String,target:File,limit:Long)
    suspend fun replaceManifest(id:String,parent:String,oldHash:String,manifest:BackupManifest)
    suspend fun deleteAsset(id:String,parent:String,hash:String)
    suspend fun inventory():List<DriveResource>
    suspend fun metadata(id:String):DriveResource?
    /** Purge token must include metadata inspection, otherwise unrelated children may be invisible. */
    suspend fun children(parent:String):List<DriveResource>
    suspend fun deleteOwned(resource:DriveResource):Boolean
    suspend fun deleteManifest(id:String,parent:String,hash:String) {
        val resource=metadata(id) ?: return
        require(resource.role=="manifest" && parent in resource.parents && resource.ownedByMe) { "Manifest ownership changed; it was kept." }
        val file=File.createTempFile("manifest-check-",".json")
        try { download(id,file,BackupManifest.MAX_MANIFEST_BYTES.toLong()); require(hashFile(file)==hash) { "Manifest changed; it was kept." }; deleteOwned(resource) }
        finally { file.delete() }
    }
}

class GoogleDriveDataSource(private val token:suspend()->String,private val checkAccount:()->Unit,
    private val invalidate:suspend(String)->Unit,private val open:(URL)->HttpURLConnection = { it.openConnection() as HttpURLConnection }) : DriveStore {
    private val api="https://www.googleapis.com/drive/v3/files"
    private fun escaped(value:String)=value.replace("\\","\\\\").replace("'","\\'")
    private fun query(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun connection(address:String,method:String,access:String):HttpURLConnection {
        val url=URL(address)
        require(url.protocol=="https" && url.host=="www.googleapis.com" && (url.path.startsWith("/drive/v3/") || url.path.startsWith("/upload/drive/v3/"))) { "Invalid Drive endpoint." }
        return open(url).apply {
            requestMethod=method; instanceFollowRedirects=false; connectTimeout=30000; readTimeout=30000
            setRequestProperty("Authorization","Bearer $access")
        }
    }
    private suspend fun <T> request(address:String,method:String,body:ByteArray?=null,headers:Map<String,String> = emptyMap(),
        accepted:Set<Int> = setOf(200,201),read:(HttpURLConnection)->T):T {
        for(attempt in 0..1) {
            currentCoroutineContext().ensureActive(); checkAccount(); val access=token(); val conn=connection(address,method,access)
            try {
                headers.forEach { (k,v)->conn.setRequestProperty(k,v) }
                if(body!=null) { conn.doOutput=true; conn.setFixedLengthStreamingMode(body.size); conn.outputStream.use { it.write(body) } }
                val code=conn.responseCode
                if(code==401 && attempt==0) { invalidate(access); continue }
                if(code !in accepted) {
                    val detail=conn.errorStream?.use { boundedRead(it,65536).toString(Charsets.UTF_8) }.orEmpty()
                    val reason=runCatching { JSONObject(detail).getJSONObject("error").getJSONArray("errors").getJSONObject(0).getString("reason") }.getOrDefault("")
                    throw DriveFailure(code,reason)
                }
                checkAccount(); currentCoroutineContext().ensureActive(); return read(conn)
            } finally { conn.disconnect() }
        }
        throw BackupAuthRequired()
    }
    private fun json(conn:HttpURLConnection)=conn.inputStream.use { input ->
        val bytes=boundedRead(input,BackupManifest.MAX_MANIFEST_BYTES); JSONObject(bytes.toString(Charsets.UTF_8))
    }
    override suspend fun list(parent:String?,role:String,hash:String?,includeTrashed:Boolean):List<DriveFile> {
        val q=buildString {
            if(!includeTrashed) append("trashed = false and ")
            append("appProperties has { key='folioRole' and value='${escaped(role)}' }")
            if(parent!=null) append(" and '${escaped(parent)}' in parents")
            if(hash!=null) append(" and appProperties has { key='sha256' and value='${escaped(hash)}' }")
        }
        val result=mutableListOf<DriveFile>(); var page=""
        do {
            val response=request("$api?q=${query(q)}&fields=nextPageToken,incompleteSearch,files(id,name,createdTime,appProperties)&pageSize=1000&pageToken=${query(page)}","GET",read=::json)
            check(!response.optBoolean("incompleteSearch")) { "Drive returned an incomplete backup listing. Removal must retry." }
            val files=response.optJSONArray("files") ?: JSONArray()
            repeat(files.length()) { val f=files.getJSONObject(it); result+=DriveFile(f.getString("id"),f.getString("name"),f.optString("createdTime"),f.optJSONObject("appProperties")?.optString("sha256").orEmpty()) }
            require(result.size<=100000) { "Too many Folio backup files." }; page=response.optString("nextPageToken")
        } while(page.isNotEmpty())
        return result
    }
    override suspend fun folder():String = list(null,"root").minByOrNull { it.id }?.id ?: createRoot()
    internal suspend fun createRoot():String = request(api,"POST",
        JSONObject().put("name","Folio Backup").put("mimeType","application/vnd.google-apps.folder").put("appProperties",JSONObject().put("folioRole","root")).toString().toByteArray(),
        mapOf("Content-Type" to "application/json"),read=::json).getString("id")
    override suspend fun exists(id:String):Boolean = try { request("$api/${query(id)}?fields=id,trashed","GET",read=::json).optBoolean("trashed").not() } catch(e:DriveFailure) { if(e.code==404) false else throw e }
    private suspend fun owned(id:String,parent:String,role:String):DriveResource? = try {
        request("$api/${query(id)}?fields=id,parents,appProperties,trashed", "GET") { connection ->
            val data=json(connection); val properties=data.optJSONObject("appProperties")
            if(!(properties?.optString("folioRole")==role &&
                (data.optJSONArray("parents") ?: JSONArray()).let { parents -> (0 until parents.length()).any { parents.getString(it)==parent } })) {
                throw DriveOwnershipReview("Ownership or parent changed for Drive resource $id. Restore its Folio association before retrying; it was kept.")
            }
            DriveResource(id,role,listOf(parent),etag=connection.getHeaderField("ETag").orEmpty())
        }
    } catch(error:DriveFailure) { if(error.code==404) null else throw error }
    override suspend fun replaceManifest(id:String,parent:String,oldHash:String,manifest:BackupManifest) {
        val resource=owned(id,parent,"manifest") ?: return
        // Verify the latest bytes being replaced, independently of stale app checksum metadata.
        try { request("$api/${query(id)}?alt=media","GET") { c ->
            require(sha256(c.inputStream.use { boundedRead(it,BackupManifest.MAX_MANIFEST_BYTES) })==oldHash) { "Drive manifest changed during removal. Retry to read its latest version." }
        } } catch(error:DriveFailure) { if(error.code==404) return else throw error }
        val bytes=manifest.encode(); val boundary="folio-${java.util.UUID.randomUUID()}"
        val metadata=JSONObject().put("appProperties",JSONObject().put("folioRole","manifest").put("sha256",sha256(bytes)))
        val body=("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n"+
            "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray()+bytes+"\r\n--$boundary--\r\n".toByteArray()
        request("https://www.googleapis.com/upload/drive/v3/files/${query(id)}?uploadType=multipart&fields=id", "PATCH",body,
            mapOf("Content-Type" to "multipart/related; boundary=$boundary")+conditional(resource),accepted=setOf(200,201,404)) { if(it.responseCode!=404) json(it) }
    }
    override suspend fun deleteAsset(id:String,parent:String,hash:String) {
        val resource=owned(id,parent,"asset") ?: return
        request("$api/${query(id)}", "DELETE",headers=conditional(resource),accepted=setOf(204,404)) { }
        check(metadata(id)==null) { "Drive has not confirmed removal of resource $id. Retry will verify it again." }
    }
    override suspend fun deleteManifest(id:String,parent:String,hash:String) {
        val resource=owned(id,parent,"manifest") ?: return
        val current=metadata(id) ?: return
        if(!current.ownedByMe || current.folder) throw DriveOwnershipReview("Manifest ownership is ambiguous; it was kept.")
        try { request("$api/${query(id)}?alt=media","GET") { c ->
            if(sha256(c.inputStream.use { boundedRead(it,BackupManifest.MAX_MANIFEST_BYTES) })!=hash) throw DriveOwnershipReview("Manifest changed during cleanup; it was kept.")
        } } catch(error:DriveFailure) { if(error.code==404) return else throw error }
        request("$api/${query(id)}","DELETE",headers=conditional(resource),accepted=setOf(204,404)) {}
        check(metadata(id)==null) { "Manifest removal is not yet verified." }
    }
    private fun conditional(resource:DriveResource)=resource.etag.takeIf { it.isNotEmpty() }?.let { mapOf("If-Match" to it) }.orEmpty()
    private val resourceFields="id,mimeType,parents,appProperties,ownedByMe,version,trashed"
    private fun resource(data:JSONObject,etag:String=""):DriveResource {
        val parents=data.optJSONArray("parents") ?: JSONArray()
        return DriveResource(data.getString("id"),data.optJSONObject("appProperties")?.optString("folioRole").orEmpty(),
            (0 until parents.length()).map { parents.getString(it) },data.optString("mimeType")=="application/vnd.google-apps.folder",data.optBoolean("ownedByMe",false),data.optString("version"),etag)
    }
    override suspend fun metadata(id:String):DriveResource? = try {
        request("$api/${query(id)}?fields=$resourceFields","GET") { c -> resource(json(c),c.getHeaderField("ETag").orEmpty()) }
    } catch(error:DriveFailure) { if(error.code==404) null else throw error }
    private suspend fun resources(q:String):List<DriveResource> {
        val result=mutableListOf<DriveResource>(); var page=""
        do {
            val response=request("$api?q=${query(q)}&fields=nextPageToken,incompleteSearch,files($resourceFields)&pageSize=1000&pageToken=${query(page)}","GET",read=::json)
            check(!response.optBoolean("incompleteSearch")) { "Drive returned an incomplete inventory. Nothing is marked complete." }
            val files=response.optJSONArray("files") ?: JSONArray()
            repeat(files.length()) { result+=resource(files.getJSONObject(it)) }
            require(result.size<=100000) { "Drive inventory exceeds the safe 100,000-resource limit." }
            page=response.optString("nextPageToken")
        } while(page.isNotEmpty())
        return result
    }
    override suspend fun inventory()=resources(listOf("root","manifest","asset").joinToString(" or ","(",")") { "appProperties has { key='folioRole' and value='$it' }" })
    override suspend fun children(parent:String)=resources("'${escaped(parent)}' in parents")
    override suspend fun deleteOwned(resource:DriveResource):Boolean {
        val current=metadata(resource.id) ?: return false
        if(current.role!=resource.role || current.role !in listOf("root","manifest","asset") || !current.ownedByMe || current.folder!=resource.folder)
            throw DriveOwnershipReview("Ownership is ambiguous for Drive resource ${resource.id}; no deletion was requested.")
        if(current.folder && children(current.id).isNotEmpty()) throw DriveOwnershipReview("Folder ${current.id} still contains files. Folio left it intact to protect unrelated data.")
        val deleted=request("$api/${query(current.id)}","DELETE",headers=conditional(current),accepted=setOf(204,404)) { it.responseCode==204 }
        check(metadata(current.id)==null) { "Drive resource ${current.id} remains retrievable after deletion." }
        return deleted
    }
    private data class UploadProgress(val id:String?=null,val offset:Long=0,val expired:Boolean=false)
    private suspend fun probe(session:String,total:Long):UploadProgress = try {
        request(session,"PUT",byteArrayOf(),mapOf("Content-Range" to "bytes */$total"),setOf(200,201,308)) { c ->
            if(c.responseCode==308) UploadProgress(offset=c.getHeaderField("Range")?.substringAfterLast('-')?.toLong()?.plus(1) ?: 0)
            else UploadProgress(id=json(c).getString("id"))
        }
    } catch(e:DriveFailure) { if(e.code==404 || e.code==410) UploadProgress(expired=true) else throw e }
    override suspend fun upload(parent:String,name:String,role:String,hash:String,file:File,session:String?,checkpoint:suspend(String)->Unit):String {
        var active=session; var offset=0L
        if(active!=null) {
            val progress=probe(active,file.length()); progress.id?.let { return it }
            require(progress.offset in 0..file.length()) { "Invalid Drive upload range." }
            if(progress.expired) active=null else offset=progress.offset
        }
        if(active==null) {
            val metadata=JSONObject().put("name",name).put("parents",JSONArray(listOf(parent)))
                .put("appProperties",JSONObject().put("folioRole",role).put("sha256",hash))
            active=request("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id","POST",metadata.toString().toByteArray(),
                mapOf("Content-Type" to "application/json; charset=UTF-8","X-Upload-Content-Type" to if(role=="manifest") "application/json" else "application/octet-stream","X-Upload-Content-Length" to file.length().toString())) { it.getHeaderField("Location") ?: throw IOException("Drive did not start the upload.") }
            checkpoint(active)
        }
        val endpoint=active
        RandomAccessFile(file,"r").use { input ->
            while(offset<file.length()) {
                currentCoroutineContext().ensureActive(); checkAccount(); require(offset>=0) { "Invalid Drive upload range." }
                input.seek(offset); val chunk=ByteArray(minOf(1024L*1024,file.length()-offset).toInt()); input.readFully(chunk)
                val progress=try { request(endpoint,"PUT",chunk,mapOf("Content-Type" to "application/octet-stream","Content-Range" to "bytes $offset-${offset+chunk.size-1}/${file.length()}"),setOf(200,201,308)) { c ->
                    if(c.responseCode==308) UploadProgress(offset=c.getHeaderField("Range")?.substringAfterLast('-')?.toLong()?.plus(1) ?: 0)
                    else UploadProgress(id=json(c).getString("id"))
                } } catch(e:DriveFailure) { if(e.code==404 || e.code==410) throw IOException("Upload session expired. Retry will start a new session."); throw e }
                progress.id?.let { return it }; require(progress.offset>offset && progress.offset<=file.length()) { "Drive did not acknowledge the upload chunk." }; offset=progress.offset
            }
        }
        return probe(endpoint,file.length()).id ?: throw IOException("Drive upload has not completed.")
    }
    override suspend fun download(id:String,target:File,limit:Long) {
        val job=currentCoroutineContext()
        request("$api/${query(id)}?alt=media","GET") { c ->
            val writing=File(target.path+".writing"); target.parentFile!!.mkdirs()
            try {
                c.inputStream.use { input -> writing.outputStream().use { out ->
                    val buffer=ByteArray(65536); var total=0L
                    while(true) { job.ensureActive(); checkAccount(); val n=input.read(buffer); if(n<0) break; total+=n; require(total<=limit) { "Downloaded backup file exceeds its declared size." }; out.write(buffer,0,n) }
                } }; check(writing.renameTo(target)) { "Could not save downloaded backup file." }
            } finally { writing.delete() }
        }
    }
    private fun boundedRead(input:java.io.InputStream,limit:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
        while(true) { val n=input.read(buffer); if(n<0) break; require(output.size()+n<=limit) { "Drive response is too large." }; output.write(buffer,0,n) }
        return output.toByteArray()
    }
}

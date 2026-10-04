package dev.folio.scanner.backup

import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriveTransportTest {
    private fun resourceJson(id:String="owned",role:String="asset",folder:Boolean=false,owner:Boolean=true)="""{"id":"$id","mimeType":"${if(folder) "application/vnd.google-apps.folder" else "application/octet-stream"}","parents":["root"],"ownedByMe":$owner,"appProperties":{"folioRole":"$role","sha256":"changed"}}"""
    @Test fun purgeInventoryPaginatesAndIncludesTrashedResourcesWithoutNameHeuristics()=runBlocking {
        val requests=mutableListOf<Response>()
        val source=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,200,if(requests.isEmpty()) """{"nextPageToken":"next","files":[${resourceJson("first") }]}""" else """{"files":[${resourceJson("second","manifest")}]}""").also { requests+=it } })
        assertEquals(listOf("first","second"),source.inventory().map { it.id })
        val query=java.net.URLDecoder.decode(requests.first().url.query,"UTF-8")
        assertFalse(query.contains("trashed = false")); assertFalse(query.contains("name=")); assertTrue(requests.last().url.query.contains("pageToken=next"))
        val incomplete=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,200,"""{"incompleteSearch":true,"files":[]}""") })
        try { incomplete.inventory(); fail("Incomplete inventory accepted") } catch(_:IllegalStateException) {}
    }
    @Test fun purgeVerifiesAbsenceAndNeverDeletesUnrelatedChildrenOrUnownedResources()=runBlocking {
        for(folder in listOf(false,true)) {
            val requests=mutableListOf<Response>()
            val source=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,200,if(requests.isEmpty()) resourceJson(role=if(folder) "root" else "asset",folder=folder,owner=folder) else """{"files":[${resourceJson("unrelated","")}]}""").also { requests+=it } })
            try { source.deleteOwned(DriveResource("owned",if(folder) "root" else "asset",listOf("root"),folder)); fail("Unsafe deletion accepted") } catch(_:DriveOwnershipReview) {}
            assertTrue(requests.all { it.requestMethod=="GET" })
        }
        val requests=mutableListOf<Response>()
        val stillPresent=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,if(requests.size==1) 204 else 200,resourceJson()).also { requests+=it } })
        try { stillPresent.deleteOwned(DriveResource("owned","asset",listOf("root"))); fail("DELETE response incorrectly completed purge") } catch(_:IllegalStateException) {}
        assertEquals(listOf("GET","DELETE","GET"),requests.map { it.requestMethod })
        var calls=0
        val changed=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,when(calls++) { 0 -> 200; 1 -> 204; else -> 404 },resourceJson()) })
        assertTrue(changed.deleteOwned(DriveResource("owned","asset",listOf("root"))))
        val missing=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,404) })
        assertFalse(missing.deleteOwned(DriveResource("missing","asset",emptyList())))
    }
    @Test fun positivelyOwnedDeletionDoesNotRequireUndocumentedV3EtagHeader()=runBlocking {
        var calls=0
        val source=GoogleDriveDataSource({"test-token"},{},{},{ url ->
            Response(url,when(calls++) { 0 -> 200; 1 -> 204; else -> 404 },"{\"parents\":[\"root\"],\"appProperties\":{\"folioRole\":\"asset\",\"sha256\":\"hash\"}}")
        })
        source.deleteAsset("asset-id","root","hash")
        assertEquals(3,calls)
    }

    @Test fun assetDeletionRequiresPositiveParentRoleAndOptionalConditionalVersion()=runBlocking {
        val requests=mutableListOf<Response>()
        val source=GoogleDriveDataSource({"test-token"},{},{},{ url ->
            Response(url,when(requests.size) { 0 -> 200; 1 -> 204; else -> 404 },"{\"parents\":[\"root\"],\"appProperties\":{\"folioRole\":\"asset\",\"sha256\":\"hash\"}}",mapOf("ETag" to "version-1")).also { requests+=it }
        })
        source.deleteAsset("asset-id","root","hash")
        assertEquals("version-1",requests.single { it.requestMethod=="DELETE" }.getRequestProperty("If-Match")); assertEquals("GET",requests.last().requestMethod)
        val unrelated=mutableListOf<Response>()
        val unsafe=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,200,"{\"parents\":[\"other-root\"],\"appProperties\":{\"folioRole\":\"asset\",\"sha256\":\"hash\"}}",mapOf("ETag" to "version")).also { unrelated+=it } })
        try { unsafe.deleteAsset("asset-id","root","hash"); fail("Unrelated file deleted") } catch(_:IllegalArgumentException) {}
        assertEquals(1,unrelated.size); assertEquals("GET",unrelated.single().requestMethod)
        val absent=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,404) })
        absent.deleteAsset("already-missing","root","hash")
    }
    @Test fun manifestPatchUpdatesContentAndChecksumAtomicallyAndAuthFailureRemainsRetryable()=runBlocking {
        val requests=mutableListOf<Response>(); val manifest=BackupManifest(java.util.UUID.randomUUID().toString(),1,emptyList(),emptyList(),emptyList(),emptyList()); val hash=sha256(manifest.encode())
        val source=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,200,
            if(requests.isEmpty()) "{\"parents\":[\"root\"],\"appProperties\":{\"folioRole\":\"manifest\",\"sha256\":\"$hash\"}}" else if(url.query.contains("alt=media")) manifest.encode().toString(Charsets.UTF_8) else "{\"id\":\"manifest-id\"}",mapOf("ETag" to "manifest-version")).also { requests+=it } })
        source.replaceManifest("manifest-id","root",hash,manifest)
        val patch=requests.last(); assertEquals("PATCH",patch.requestMethod)
        assertEquals("manifest-version",patch.getRequestProperty("If-Match")); assertTrue(patch.url.query.contains("uploadType=multipart"))
        assertTrue(patch.sent.toString("UTF-8").contains(sha256(manifest.encode())))
        assertTrue(patch.sent.toString("UTF-8").contains(manifest.encode().toString(Charsets.UTF_8)))
        var attempts=0
        val expired=GoogleDriveDataSource({"expired-token"},{},{},{ url -> attempts++; Response(url,401) })
        try { expired.deleteAsset("asset-id","root","hash"); fail("Expired authorization accepted") } catch(error:DriveFailure) { assertEquals(401,error.code) }
        assertEquals(2,attempts)
    }
    private class Response(url:URL,val code:Int,val body:String="{}",val headers:Map<String,String> = emptyMap()):HttpURLConnection(url) {
        val sent=ByteArrayOutputStream()
        override fun disconnect() {}
        override fun usingProxy()=false
        override fun connect() {}
        override fun setRequestMethod(value:String) { method=value }
        override fun getResponseCode()=code
        override fun getInputStream()=ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream()=ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream()=sent
        override fun getHeaderField(name:String)=headers[name]
    }
    @Test fun resumableUploadUsesAlignedChunksAndResumesAtServerAcknowledgedOffset()=runBlocking {
        val file=File.createTempFile("folio-upload",".bin"); file.writeBytes(ByteArray(1300000) { (it%127).toByte() })
        val requests=mutableListOf<Response>(); val replies=ArrayDeque<Pair<Int,Map<String,String>>>()
        replies.add(200 to mapOf("Location" to "https://www.googleapis.com/upload/drive/v3/files?upload_id=test"))
        replies.add(308 to mapOf("Range" to "bytes=0-1048575")); replies.add(200 to emptyMap())
        val source=GoogleDriveDataSource({"test-token"},{},{},{ url -> val reply=replies.removeFirst(); Response(url,reply.first,if(replies.isEmpty()) "{\"id\":\"uploaded_id\"}" else "{}",reply.second).also { requests+=it } })
        var checkpoint=""
        try {
            assertEquals("uploaded_id",source.upload("parent","image","asset",sha256(file.readBytes()),file,null) { checkpoint=it })
            assertTrue(checkpoint.contains("upload_id=test"))
            assertEquals("bytes 0-1048575/1300000",requests[1].getRequestProperty("Content-Range")); assertEquals(1048576,requests[1].sent.size())
            assertEquals("bytes 1048576-1299999/1300000",requests[2].getRequestProperty("Content-Range"))
            assertArrayEquals(file.readBytes(),requests[1].sent.toByteArray()+requests[2].sent.toByteArray())
            val resumed=mutableListOf<Response>(); var index=0
            val second=GoogleDriveDataSource({"test-token"},{},{},{ url -> Response(url,if(index++==0) 308 else 200,"{\"id\":\"resumed_id\"}",mapOf("Range" to "bytes=0-262143")).also { resumed+=it } })
            assertEquals("resumed_id",second.upload("parent","image","asset","hash",file,checkpoint) { fail("Existing session should be reused") })
            assertEquals("bytes */1300000",resumed[0].getRequestProperty("Content-Range"))
            assertEquals("bytes 262144-1299999/1300000",resumed[1].getRequestProperty("Content-Range"))
            assertArrayEquals(file.readBytes().copyOfRange(262144,1300000),resumed[1].sent.toByteArray())
        } finally { file.delete() }
    }
    @Test fun unauthorizedResponseInvalidatesTokenOnceAndNeverFollowsRedirects()=runBlocking {
        var expired=true; var cleared=0; val requests=mutableListOf<Response>()
        val source=GoogleDriveDataSource({if(expired) "expired-test-token" else "fresh-test-token"},{},{expired=false;cleared++},{ url -> Response(url,if(expired) 401 else 200,"{\"id\":\"file\",\"trashed\":false}").also { requests+=it } })
        assertTrue(source.exists("file")); assertEquals(1,cleared); assertEquals(2,requests.size)
        assertFalse(requests.first().instanceFollowRedirects); assertEquals("Bearer fresh-test-token",requests.last().getRequestProperty("Authorization"))
    }
    @Test fun uploadSessionOnAnotherHostIsRejectedBeforeCredentialsAreSent()=runBlocking {
        val file=File.createTempFile("folio-url",".bin").apply { writeText("image") }
        val source=GoogleDriveDataSource({"test-token"},{},{},{ error("Must not open an untrusted endpoint") })
        try { try { source.upload("parent","image","asset","hash",file,"https://other.example/upload") {}; fail("Untrusted upload accepted") } catch(_:IllegalArgumentException) {} }
        finally { file.delete() }
    }
}

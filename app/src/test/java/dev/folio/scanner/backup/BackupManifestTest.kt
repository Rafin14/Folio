package dev.folio.scanner.backup

import dev.folio.scanner.data.*
import dev.folio.scanner.processing.Enhancement
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupManifestTest {
    @Test fun pageTrashRoundTripsAndRemovalKeepsOtherTrashedPages() {
        val m=fixture(); val trash=m.pages.last().copy(page=m.pages.last().page.copy(position=1000001,trashedAt=40,trashPosition=1,trashDocumentTitle=doc.title))
        val archived=m.copy(documents=listOf(doc.copy(pageCount=1)),pages=listOf(m.pages.first(),trash))
        archived.validate(); val round=BackupManifest.decode(archived.encode())
        assertEquals(40L,round.pages.last().page.trashedAt); assertEquals(1,round.pages.last().page.trashPosition)
        val removed=archived.withoutPages(setOf(m.pages.first().page.id)); removed.validate()
        assertEquals(0,removed.documents.single().pageCount); assertEquals(1000001,removed.pages.single().page.position)
        assertEquals(m.assets,removed.assets)
    }

    private val doc=Document(UUID.randomUUID().toString(),"Father's notes",10,20,pageCount=2,favorite=true)
    private val hash=sha256("image".toByteArray())
    private val pages=List(2) { BackupPage(Page(UUID.randomUUID().toString(),doc.id,it,"original","processed","thumb",1200,1600,90,"0.0,0.0;1.0,0.0;1.0,1.0;0.0,1.0",Enhancement("Auto",sharpness=.4).encode()),hash,hash) }
    private fun fixture()=BackupManifest(UUID.randomUUID().toString(),30,emptyList(),listOf(doc),pages,listOf(BackupAsset(hash,5,"drive_file_1")))
    private fun invalid(block:()->Unit) { try { block(); fail("Expected corrupt backup rejection") } catch(_:IllegalArgumentException) {} }
    @Test fun pageRemovalKeepsDocumentReordersSurvivorsAndRetainsSharedAssets() {
        val m=fixture(); val kept=m.withoutPages(setOf(pages.first().page.id))
        kept.validate(); assertEquals(doc.id,kept.documents.single().id); assertEquals(1,kept.documents.single().pageCount)
        assertEquals(pages.last().page.id,kept.pages.single().page.id); assertEquals(0,kept.pages.single().page.position)
        assertEquals(m.assets,kept.assets)
        val empty=kept.withoutPages(setOf(pages.last().page.id)); empty.validate()
        assertEquals(0,empty.documents.single().pageCount); assertTrue(empty.assets.isEmpty())
        val receipt=BackupRecord("local:delete:page:${pages.first().page.id}:${doc.id}","",pages.first().page.id,0,1,"local-page-deleted")
        assertEquals(kept,m.withoutDeleted(listOf(receipt)))
    }
    @Test fun deterministicRoundTripPreservesOrderCropEditsAndTrash() {
        val manifest=fixture().copy(documents=listOf(doc.copy(trashedAt=25)))
        val decoded=BackupManifest.decode(manifest.encode())
        assertEquals(manifest.documents,decoded.documents); assertEquals(pages.map { it.original },decoded.pages.map { it.original })
        assertEquals(listOf(0,1),decoded.pages.map { it.page.position }); assertEquals(pages[0].page.enhancement,decoded.pages[0].page.enhancement)
        assertArrayEquals(manifest.encode(),manifest.copy(pages=pages.reversed()).encode())
        assertArrayEquals(manifest.encode(),decoded.encode())
    }
    @Test fun manifestChecksumAndUnsupportedSchemaAreRejected() {
        val envelope=JSONObject(fixture().encode().toString(Charsets.UTF_8)); val body=envelope.getJSONObject("backup")
        body.put("createdAt",999); invalid { BackupManifest.decode(envelope.toString().toByteArray()) }
        body.put("schema",99); envelope.put("sha256",sha256(canonical(body).toByteArray()))
        invalid { BackupManifest.decode(envelope.toString().toByteArray()) }
    }
    @Test fun invalidRelationshipsOrderMetadataAndAssetDescriptorsAreRejected() {
        val m=fixture()
        invalid { m.copy(documents=listOf(doc.copy(folderId=UUID.randomUUID().toString()))).validate() }
        invalid { m.copy(pages=listOf(pages[0],pages[1].copy(page=pages[1].page.copy(position=0)))).validate() }
        invalid { m.copy(pages=pages+pages[0]).validate() }
        invalid { m.copy(assets=emptyList()).validate() }
        invalid { m.copy(assets=listOf(BackupAsset(hash,5,"../../bad"))).validate() }
        invalid { m.copy(pages=pages.map { it.copy(page=it.page.copy(rotation=45)) }).validate() }
        invalid { m.copy(pages=pages.map { it.copy(page=it.page.copy(enhancement="Auto|NaN|1|1|0|12|0")) }).validate() }
        invalid { m.copy(pages=pages.map { it.copy(page=it.page.copy(crop="-1,0;1,0;1,1;0,1")) }).validate() }
    }
    @Test fun streamingHashMatchesKnownDigestAndDetectsChanges() {
        val file=File.createTempFile("folio-hash",".bin")
        try { file.writeText("abc"); assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",hashFile(file)); file.appendText("d"); assertNotEquals(sha256("abc".toByteArray()),hashFile(file)) } finally { file.delete() }
    }
    @Test fun incrementalFingerprintIncludesNamesOrderAndEditsButIgnoresThumbnails() {
        val snapshot=LibrarySnapshot(emptyList(),listOf(doc),pages.map { it.page })
        val original=BackupPlanner.fingerprint(snapshot)
        assertEquals(original,BackupPlanner.fingerprint(snapshot.copy(pages=snapshot.pages.map { it.copy(thumbnailUri="new-cache") })))
        assertNotEquals(original,BackupPlanner.fingerprint(snapshot.copy(documents=listOf(doc.copy(title="Renamed")))))
        assertNotEquals(original,BackupPlanner.fingerprint(snapshot.copy(pages=snapshot.pages.map { it.copy(position=1-it.position) })))
        assertNotEquals(original,BackupPlanner.fingerprint(snapshot.copy(pages=snapshot.pages.map { it.copy(processedImageUri="new-version.jpg") })))
    }
    @Test fun transientDriveErrorsRetryButAuthQuotaAndCorruptionDoNot() {
        listOf(408,429,500,502,503,504).forEach { assertTrue(BackupPlanner.retryable(it)) }
        assertTrue(BackupPlanner.retryable(403,"rateLimitExceeded"))
        listOf(400,401,404).forEach { assertFalse(BackupPlanner.retryable(it)) }
        assertFalse(BackupPlanner.retryable(403,"storageQuotaExceeded"))
        assertFalse(BackupPlanner.retryable(403,"insufficientPermissions"))
    }
    @Test fun stateTransitionsKeepDisconnectedAndPausedDistinctFromFailure() {
        assertEquals("Not connected",BackupPlanner.status(false,false,true,false))
        assertEquals("Backup paused",BackupPlanner.status(true,false,true,false))
        assertEquals("Backup pending",BackupPlanner.status(true,false,true,true))
        assertEquals("Backing up…",BackupPlanner.status(true,true,true,true))
        assertEquals("Up to date",BackupPlanner.status(true,false,false,true))
        assertEquals("Backup failed",BackupPlanner.status(true,false,true,true,true))
    }
}

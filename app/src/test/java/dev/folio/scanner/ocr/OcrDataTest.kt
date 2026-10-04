package dev.folio.scanner.ocr

import com.paddle.ocr.postprocess.CTCDecoder
import dev.folio.scanner.data.*
import dev.folio.scanner.backup.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class OcrDataTest {
    private val doc=Document(UUID.randomUUID().toString(),"Notes",1,2,pageCount=1)
    private val page=Page(UUID.randomUUID().toString(),doc.id,0,"original","processed","thumb",1200,1600)
    private val hash=sha256("original".toByteArray())
    private val regions=listOf(TextRegion("Newton second law",listOf(.1,.1,.8,.1,.8,.2,.1,.2),.72))
    private fun result()=OcrResult(page.id,"Newton second law",3,regionsJson(regions),"complete",ocrRevision(page,hash),hash,"en","PaddleOCR",OCR_MODEL,OCR_PREPROCESS,1200,1600,20)
    @Test fun ctcRemovesBlanksAndRepeatsWithoutDroppingLowConfidenceText() {
        val classes=listOf("A","B"," "); val indices=listOf(1,1,0,1,3,2)
        val scores=FloatArray(indices.size*4) { .1f }; indices.forEachIndexed { t,c -> scores[t*4+c]=.4f }
        val decoded=CTCDecoder.decode(scores,longArrayOf(1,6,4),classes).single()
        assertEquals("AA B",decoded.first); assertEquals(.4f,decoded.second,.001f)
    }
    @Test fun dictionaryMappingCoversActualRecognitionClassCount() {
        val a=org.json.JSONArray(java.io.File("src/main/assets/models/ocr/rec/characters.json").readText())
        assertEquals(18709,a.length()); assertEquals(" ",a.getString(a.length()-1)); assertEquals("!",a.getString(0))
    }
    @Test fun structuredRegionsPreserveCoordinatesTextAndConfidence() { assertEquals(regions,parseRegions(regionsJson(regions))) }
    @Test fun malformedRegionCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { parseRegions(regionsJson(regions.map { it.copy(points=listOf(-.1,.1,.8,.1,.8,.2,.1,.2)) })) }
    }
    @Test fun revisionsIgnoreOrderAndThumbButInvalidateCropRotationFiltersAndModel() {
        val r=result(); assertTrue(validOcr(r,page.copy(position=4,thumbnailUri="new",pageName="Renamed")))
        assertTrue(sameOcrPage(page.copy(position=4,pageName="Renamed"),page))
        assertFalse(sameOcrPage(page.copy(pageSize="A4"),page)); assertFalse(validOcr(r,page.copy(pageSize="A4")))
        assertFalse(validOcr(r,page.copy(rotation=90))); assertFalse(validOcr(r,page.copy(crop="changed")))
        assertFalse(validOcr(r,page.copy(enhancement="Grayscale"))); assertFalse(validOcr(r.copy(modelVersion="future"),page))
        assertFalse(validOcr(r.copy(preprocessingVersion="future"),page)); assertFalse(validOcr(r.copy(status="processing"),page))
    }
    @Test fun searchTokensAreSafeAndUseFts4PrefixSyntax() {
        assertEquals("\"Newton*\" \"law*\"",searchExpression("Newton law")); assertEquals("",searchExpression("*\"()"))
        assertEquals("\"invoice*\" \"2026*\"",searchExpression("invoice:2026"))
    }
    @Test fun backupSchemaTwoPreservesOcrAndRejectsStaleCoordinates() {
        val m=BackupManifest(UUID.randomUUID().toString(),4,emptyList(),listOf(doc),listOf(BackupPage(page,hash,hash)),listOf(BackupAsset(hash,5,"remote")),listOf(result()))
        assertEquals(m.ocr,BackupManifest.decode(m.encode()).ocr)
        assertThrows(IllegalArgumentException::class.java) { m.copy(ocr=listOf(result().copy(revision="old"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { m.copy(ocr=listOf(result().copy(text="tampered"))).validate() }
    }
    @Test fun legacyBackupSchemaOneStillRestoresWithoutOcr() {
        val m=BackupManifest(UUID.randomUUID().toString(),4,emptyList(),listOf(doc),listOf(BackupPage(page,hash,hash)),listOf(BackupAsset(hash,5,"remote")))
        val body=m.body().put("schema",1); body.remove("ocr")
        val e=JSONObject().put("backup",body).put("sha256",sha256(canonical(body).toByteArray()))
        assertTrue(BackupManifest.decode(e.toString().toByteArray()).ocr.isEmpty())
    }
    @Test fun incrementalBackupFingerprintIncludesOcrWithoutUploadingTemporaryAssets() {
        val a=LibrarySnapshot(emptyList(),listOf(doc),listOf(page)); val b=a.copy(ocr=listOf(result()))
        assertNotEquals(BackupPlanner.fingerprint(a),BackupPlanner.fingerprint(b)); assertNotEquals(BackupPlanner.documentFingerprint(a,doc),BackupPlanner.documentFingerprint(b,doc))
    }
}

package dev.folio.scanner.backup

import dev.folio.scanner.data.Document
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ManagedPdfManifestTest {
    @Test fun nativePdfAssetAndIdentityRoundTripAndDocumentRemovalDropsItsReference() {
        val hash="a".repeat(64)
        val doc=Document(UUID.randomUUID().toString(),"PDF",1,2,pdfHash=hash,pdfRevision=2)
        val manifest=BackupManifest(UUID.randomUUID().toString(),2,emptyList(),listOf(doc),emptyList(),listOf(BackupAsset(hash,10,"drive-id")))
        val restored=BackupManifest.decode(manifest.encode())
        assertEquals(doc,restored.documents.single())
        assertEquals(hash,restored.assets.single().hash)
        assertTrue(restored.withoutDocuments(setOf(doc.id)).assets.isEmpty())
        assertEquals(hash,restored.withoutPages(emptySet()).assets.single().hash)
    }
}

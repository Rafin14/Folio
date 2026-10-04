package dev.folio.scanner

import dev.folio.scanner.data.DocumentRepository

/** Tests explicitly trash then purge only their own fixtures; user Delete stays reversible. */
internal suspend fun DocumentRepository.purgeForTest(id: String) {
    val doc=dao.document(id) ?: return
    if(doc.trashedAt==null) delete(id)
    permanentlyDelete(setOf(id))
}

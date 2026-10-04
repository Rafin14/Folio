package dev.folio.scanner.data

import org.junit.Assert.*
import org.junit.Test

class DocumentPolicyTest {
    @Test fun namesAreTrimmedCaseInsensitiveActiveOnlyAndSelfRenameAllowed() {
        val active=Document("id","Physics Notes",0,0)
        for(name in listOf("Physics Notes","physics notes","  PHYSICS NOTES  ")) assertNotNull(documentNameConflict(name,listOf(active)))
        assertNull(documentNameConflict("Physics Notes",listOf(active),"id"))
        assertNull(documentNameConflict("Physics Notes",listOf(active.copy(trashedAt=1))))
        assertNull(documentNameConflict("Physics Notes",listOf(active.copy(deleting=true))))
        assertNull(documentNameConflict("Physics",listOf(active)))
    }
    @Test fun expiryIsExact60DaysAndRestoreAndFutureTimestampsAreExcluded() {
        val start=1_700_000_000_000L
        assertFalse(trashExpired(start,start+TRASH_RETENTION_MS-1))
        assertTrue(trashExpired(start,start+TRASH_RETENTION_MS))
        assertFalse(trashExpired(null,start+TRASH_RETENTION_MS))
        assertFalse(trashExpired(start,start-1))
        assertEquals(60,trashDaysRemaining(start,start))
        assertEquals(43,trashDaysRemaining(start,start+17*86_400_000L))
        assertEquals(1,trashDaysRemaining(start,start+TRASH_RETENTION_MS-1))
        assertEquals(0,trashDaysRemaining(start,start+TRASH_RETENTION_MS))
    }
}

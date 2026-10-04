package dev.folio.scanner.backup
import org.junit.Assert.*
import org.junit.Test
class CloudPurgeTest {
    @Test fun confirmationIsExactUppercaseWithoutWhitespace() {
        assertTrue(validPurgeConfirmation("DELETE"))
        for(value in listOf("delete","Delete"," DELETE","DELETE ","DELETE\n","")) assertFalse(validPurgeConfirmation(value))
    }
    @Test fun diagnosticsDoNotExposeMalformedDocumentContents() {
        val reason=removalReason(NumberFormatException("For input string: private document text"))
        assertFalse(reason.contains("private document text")); assertTrue(reason.contains("validation"))
        assertTrue(removalReason(DriveOwnershipReview("Ownership is ambiguous for resource id.")).contains("Ownership"))
    }
}

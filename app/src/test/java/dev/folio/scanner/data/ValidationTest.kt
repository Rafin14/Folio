package dev.folio.scanner.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    @Test fun namesAreTrimmedAndValidated() {
        assertEquals("Receipts", validatedTitle("  Receipts  "))
        assertThrows(IllegalArgumentException::class.java) { validatedTitle(" \n ") }
        assertThrows(IllegalArgumentException::class.java) { validatedTitle("a".repeat(121)) }
    }
}

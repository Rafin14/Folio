package dev.folio.scanner.ocr

import org.junit.Assert.*
import org.junit.Test

class TextMatchesTest {
    @Test fun wordsAndPhrasesAreLiteralCaseInsensitiveAndRetainEveryOccurrence() {
        val text="Invoice paid. INVOICE PAID. invoice paid."
        assertEquals(listOf(0..6,14..20,28..34),textMatches(text,"invoice"))
        assertEquals(listOf(0..11,14..25,28..39),textMatches(text,"Invoice paid"))
        assertEquals(listOf(0..2,1..3),textMatches("aaaa","aaa"))
        assertEquals(listOf(0..2),textMatches("a.b","a.b"))
        assertEquals(emptyList<IntRange>(),textMatches(text,"missing"))
        assertEquals(emptyList<IntRange>(),textMatches(text," "))
    }
    @Test fun hardwareSelectionRequiresSupportedApiProviderAndNoForcedCpu() {
        assertTrue(ocrHardwareEligible(29,true,false)); assertFalse(ocrHardwareEligible(28,true,false))
        assertFalse(ocrHardwareEligible(36,false,false)); assertFalse(ocrHardwareEligible(36,true,true))
    }
    @Test fun navigationWrapsInBothDirectionsAndHandlesNoMatches() {
        assertEquals(0,nextTextMatch(2,1,3)); assertEquals(2,nextTextMatch(0,-1,3))
        assertEquals(1,nextTextMatch(0,1,3)); assertEquals(0,nextTextMatch(0,1,0))
    }
    @Test fun offsetsIncludeUtf16SurrogatesAndPhrasesAcrossWhitespace() {
        assertEquals(listOf(3..7),textMatches("😀 hello","HELLO"))
        assertEquals(listOf(0..9),textMatches("line\nbreak","line\nbreak"))
        assertEquals(listOf(0..9),textMatches("line\nbreak","line break"))
    }
}

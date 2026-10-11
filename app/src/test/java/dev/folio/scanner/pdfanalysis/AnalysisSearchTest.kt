package dev.folio.scanner.pdfanalysis

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AnalysisSearchTest {
    private fun page(mode:OcrMode=OcrMode.LAYOUT_AWARE,index:Int=0)=AnalysisPage(index,800,1000,595.0,842.0,listOf(
        AnalysisRegion(1,"text",Box(20f,30f,400f,80f),0,"Hello world hello"),
        AnalysisRegion(2,"text",Box(20f,100f,400f,150f),1,"WORLD again")
    ),JSONArray(),"",mode=mode)
    @Test fun repeatedCaseInsensitiveWordsKeepExactOffsets() {
        val hits=searchAnalysisPage(page(),"HELLO")
        assertEquals(listOf(0..4,12..16),hits.map {it.hits.single().range})
        assertEquals(listOf(1,1),hits.map {it.hits.single().region})
        assertTrue(searchAnalysisPage(page(),"missing").isEmpty())
        assertTrue(searchAnalysisPage(page()," ").isEmpty())
    }
    @Test fun phraseCanSpanRegionsWithoutInventingWordBoxes() {
        val hit=searchAnalysisPage(page(),"hello world").last()
        assertEquals(listOf(1,2),hit.hits.map {it.region})
        assertEquals(listOf(12..16,0..4),hit.hits.map {it.range})
        assertEquals(page().regions.map {it.box},hit.hits.map {it.box})
    }
    @Test fun fullPageUsesStoredLineBoxesAndPageIndices() {
        val page=page(OcrMode.FULL_PAGE,3);val hits=searchAnalysisPage(page,"world")
        assertEquals(listOf(3,3),hits.map {it.page})
        assertEquals(page.regions.map {it.box},hits.map {it.hits.single().box})
        assertEquals(listOf(6..10,0..4),hits.map {it.hits.single().range})
    }
}

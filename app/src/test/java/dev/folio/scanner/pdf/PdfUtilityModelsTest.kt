package dev.folio.scanner.pdf

import org.junit.Assert.*
import org.junit.Test

class PdfUtilityModelsTest {
    @Test fun selectedPagesPreserveTapOrderAndRenumberAfterRemoval() {
        val a=FolioPageChoice("A","3"); val b=FolioPageChoice("A","1"); val c=FolioPageChoice("B","2")
        val selected=listOf(a,b,c)
        assertEquals(listOf(a,c),togglePageChoice(selected,b))
        assertEquals(listOf(a,c,b),togglePageChoice(togglePageChoice(selected,b),b))
        assertEquals(listOf(c),togglePageChoice(listOf(a),c,single=true))
        assertEquals(emptyList<FolioPageChoice>(),togglePageChoice(listOf(c),c,single=true))
    }
    @Test fun filenamesRangesAndOrdering() {
        assertEquals("Research Paper",pdfFileStem("Research Paper.pdf"))
        assertEquals("bad_name",pdfFileStem("bad:name.pdf"))
        assertEquals("Research Paper Page 1.pdf",splitFilename("Research Paper",listOf(1)))
        assertEquals("Research Paper Pages 3-7.pdf",splitFilename("Research Paper",(3..7).toList()))
        assertEquals(listOf(listOf(1),listOf(3,4,5)),splitGroups("1,3-5",7))
        assertEquals(listOf(listOf(1),listOf(2)),splitGroups("",2))
        assertEquals(listOf("B","C","A"),moved(listOf("A","B","C"),0,2))
        assertEquals(listOf("C","A","B"),moved(listOf("A","B","C"),2,0))
    }
    @Test(expected=IllegalArgumentException::class) fun overlappingSplitRejected() { splitGroups("1-3,3",4) }
    @Test fun annotationsStayWithStablePageIdentityAndRotate() {
        val page=UtilityPage(source=2,ink=listOf(PdfInk(0xff336699.toInt(),.004f,listOf(InkPoint(.2f,.3f)))),notes=listOf(PdfNote("A note",color=0xff336699.toInt())))
        val moved=listOf(UtilityPage(source=1),page).reversed().first()
        assertEquals(page.id,moved.id)
        val rotated=page.rotated()
        assertEquals(90,rotated.rotation); assertEquals(.7f,rotated.ink.single().points.single().x,.001f)
        assertEquals(page,UtilityPage.from(page.json()))
        val four=generateSequence(page) { it.rotated() }.drop(4).first(); assertEquals(page.rotation,four.rotation); assertEquals(page.ink.single().points.single().x,four.ink.single().points.single().x,.00001f); assertEquals(page.ink.single().points.single().y,four.ink.single().points.single().y,.00001f)
    }
}

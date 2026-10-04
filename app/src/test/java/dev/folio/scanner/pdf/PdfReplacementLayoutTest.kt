package dev.folio.scanner.pdf

import dev.folio.scanner.data.PageLayout
import org.junit.Assert.*
import org.junit.Test

class PdfReplacementLayoutTest {
    @Test fun matchingFitsTargetRegardlessOfSourceOrSelection() {
        val target=PageLayout("Custom","Fit",210.0,297.0)
        val selected=PageLayout("Legal","Fill")
        assertEquals(target,replacementLayout(true,target,selected,PageLayout("Custom","Fit",300.0,100.0),90))
        assertEquals("Fit",replacementLayout(true,target.copy(fit="Fill"),selected,null,0).fit)
    }
    @Test fun naturalPdfSizeFollowsRotationAndExplicitSizesRemainExplicit() {
        val target=PageLayout("A4")
        val natural=PageLayout("Custom","Fit",300.0,100.0)
        assertEquals(natural,replacementLayout(false,target,PageLayout(),natural,0))
        assertEquals(100.0,replacementLayout(false,target,PageLayout(),natural,90).widthMm,0.0)
        assertEquals(300.0,replacementLayout(false,target,PageLayout(),natural,90).heightMm,0.0)
        PageLayout.sizes.forEach { size ->
            val selected=if(size=="Custom") PageLayout(size,"Fit",200.0,150.0) else PageLayout(size)
            if(size!="Original") assertEquals(selected,replacementLayout(false,target,selected,natural,90))
        }
        assertEquals(PageLayout(),replacementLayout(false,target,PageLayout(),null,270))
    }
}

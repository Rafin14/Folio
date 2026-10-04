package dev.folio.scanner.data

import dev.folio.scanner.processing.*
import org.junit.Assert.*
import org.junit.Test

class PageLayoutTest {
    @Test fun presetOrientationUsesExistingPersistentPhysicalDimensions() {
        for(name in PageLayout.sizes.filter { it !in listOf("Original","Custom") }) {
            val portrait=PageLayout(name); val landscape=PageLayout(name,"Fit",297.0,210.0)
            assertEquals(portrait.dimensions!!.second to portrait.dimensions!!.first,landscape.dimensions)
            val (w,h)=landscape.canvas(1200,1600)
            assertEquals(landscape.dimensions!!.first/landscape.dimensions!!.second,w.toDouble()/h,.002)
        }
        assertNull(PageLayout("Original","Fit",297.0,210.0).dimensions)
    }

    @Test fun sizesUsePhysicalRatiosAndBoundLargeCanvases() {
        val ratios=mapOf("A4" to 210.0/297,"A5" to 148.0/210,"Letter" to 8.5/11,"Legal" to 8.5/14,"Square" to 1.0)
        ratios.forEach { (size,ratio) -> listOf("Fit","Fill").forEach { fit ->
            val (w,h)=PageLayout(size,fit).canvas(4000,3000)
            assertEquals(ratio,w.toDouble()/h,.001)
            assertTrue(w.toLong()*h<=12_510_000 && maxOf(w,h)<=8192)
        } }
        assertEquals(4000 to 3000,PageLayout().canvas(4000,3000))
        assertEquals(300.0 to 200.0,PageLayout("Custom","Fit",300.0,200.0).dimensions)
        assertThrows(IllegalArgumentException::class.java) { PageLayout("Custom","Fit",Double.NaN,200.0) }
        assertThrows(IllegalArgumentException::class.java) { PageLayout("Custom","Fit",1.0,200.0) }
    }
    @Test fun namesCanClearButRejectUnreasonableValues() {
        assertNull(pageName("  ")); assertEquals("Newton",pageName(" Newton "))
        assertThrows(IllegalArgumentException::class.java) { pageName("a".repeat(121)) }
        assertThrows(IllegalArgumentException::class.java) { pageName("a\nb") }
    }
    @Test fun nonSquareFrameOrderingDoesNotSwapTiltedPortraitAxes() {
        val corners=listOf(Corner(.60,.10),Corner(.95,.30),Corner(.50,.90),Corner(.02,.60))
        val old=Geometry.output(Geometry.order(corners),1200,3200)
        val pixel=Geometry.order(corners,1200,3200)
        val fixed=Geometry.output(pixel,1200,3200)
        assertTrue("normalized-space start chose the long edge as width",old.first>old.second)
        assertTrue("pixel-space start retains portrait axes",fixed.first<fixed.second)
        assertEquals(corners,pixel)
        val result=validatedDetection(DocumentDetectionResult(corners,.9f,1.0,DetectionSource.LCNET),1200,3200)!!
        assertEquals(pixel,result.corners)
        assertEquals(pixel,CornerSmoother().update(result.corners,1_000_000_000))
    }
}

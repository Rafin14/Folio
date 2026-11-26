package dev.folio.scanner.processing

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun cropFrameZoomsIntoAnOffCenterDocumentWithinSafeLimits() {
        val points = listOf(Corner(.55, .3), Corner(.95, .3), Corner(.95, .7), Corner(.55, .7))
        val frame = Geometry.cropFrame(points, width = 300f, height = 400f, fittedWidth = 300f, fittedHeight = 400f)
        assertEquals(2.35f, frame.zoom, .001f)
        assertEquals(.75, frame.center.x, .001)
        assertEquals(.5, frame.center.y, .001)
        assertEquals(1f, Geometry.cropFrame(Geometry.full, 300f, 400f, 300f, 400f).zoom, 0f)
        assertEquals(2.5f, Geometry.cropFrame(points.map { Corner(it.x / 4, it.y / 4) }, 300f, 400f, 300f, 400f).zoom, 0f)
    }

    @Test fun rectanglesRotationsSkewAndExtremePerspective() {
        assertEquals(999 to 1999, Geometry.output(Geometry.full, 1000, 2000))
        val rotated = listOf(Corner(.5, .1), Corner(.9, .5), Corner(.5, .9), Corner(.1, .5))
        val skew = listOf(Corner(.1, .2), Corner(.9, .1), Corner(.8, .9), Corner(.2, .8))
        val extreme = listOf(Corner(.4, .1), Corner(.6, .1), Corner(.98, .95), Corner(.02, .95))
        listOf(rotated, skew, extreme).forEach {
            assertTrue(Geometry.valid(Geometry.order(it.reversed())))
            val size = Geometry.output(Geometry.order(it), 1200, 1600)
            assertTrue(size.first > 0 && size.second > 0)
        }
    }
    @Test fun unorderedInputAndSelfIntersection() {
        val crossed = listOf(Geometry.full[0], Geometry.full[2], Geometry.full[1], Geometry.full[3])
        assertFalse(Geometry.valid(crossed))
        assertEquals(Geometry.full, Geometry.order(crossed))
        assertThrows(IllegalArgumentException::class.java) { Geometry.output(crossed, 100, 100) }
        assertThrows(IllegalArgumentException::class.java) { Geometry.order(List(4) { Corner(0.0, 0.0) }) }
        assertFalse(Geometry.valid(listOf(Corner(Double.NaN, 0.0)) + Geometry.full.take(3)))
    }
}

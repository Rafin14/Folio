package dev.folio.scanner.processing

import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer

class LCNetDetectionTest {
    private val square=listOf(Corner(.1,.1),Corner(.9,.1),Corner(.9,.9),Corner(.1,.9))
    private fun prediction(points: List<Corner> = square,confidence: Float?=.9f,source: DetectionSource=DetectionSource.LCNET)=DocumentDetectionResult(points,confidence,42.0,source)
    @Test fun unavailableXnnpackRetriesCpuAndLogsExactlyOnce() {
        val requested=mutableListOf<Boolean>(); var reports=0
        val initialized=initializeLcNetProvider({ xnn -> requested+=xnn; if(xnn) throw IllegalStateException("Unavailable"); "cpu-session" }) { reports++ }
        assertEquals("CPU",initialized.first); assertEquals("cpu-session",initialized.second); assertEquals(listOf(true,false),requested); assertEquals(1,reports)
        assertEquals("XNNPACK",initializeLcNetProvider({ "session" }) { fail("Unexpected fallback") }.first)
    }
    @Test fun bgrChwNormalizationAndBufferReuse() {
        val pixels=IntArray(65536) { 0xff804020.toInt() }; val input=FloatBuffer.allocate(196608)
        packLcNetBgr(pixels,input)
        assertEquals(32/255f,input.get(0),0f); assertEquals(64/255f,input.get(65536),0f); assertEquals(128/255f,input.get(131072),0f)
        assertEquals(0,input.position()); pixels.fill(0xff0000ff.toInt()); packLcNetBgr(pixels,input)
        assertEquals(1f,input.get(0),0f); assertEquals(0f,input.get(131072),0f)
    }
    @Test fun decodedCentroidsConvertPixelCentersAndOrderCorners() {
        val decoded=decodedHeatmapCorners(listOf(Corner(100.0,100.0),Corner(20.0,20.0),Corner(20.0,100.0),Corner(100.0,20.0)),listOf(.9f,.8f,.95f,.85f))!!
        assertEquals(Corner(20.5/128,20.5/128),decoded.corners.first()); assertEquals(.8f,decoded.confidence!!,0f)
        assertEquals(DetectionSource.LCNET,decoded.source); assertTrue(Geometry.valid(decoded.corners))
    }
    @Test fun missingDegenerateAndOutOfBoundsOutputsAreRejected() {
        assertNull(decodedHeatmapCorners(listOf(Corner(20.0,20.0)),listOf(.9f)))
        assertNull(decodedHeatmapCorners(List(4) { Corner(20.0,20.0) },List(4) { .9f }))
        assertNull(validatedDetection(prediction(square.map { Corner(it.x+1,it.y) }),1000,800))
        assertNull(validatedDetection(prediction(confidence=Float.NaN),1000,800))
        assertNull(validatedDetection(prediction(confidence=.2f),1000,800))
        assertNotNull(validatedDetection(prediction(confidence=.41194278f),400,600))
        assertNotNull(validatedDetection(prediction(confidence=.5f),400,600))
    }
    @Test fun blankOutputAndWrongTensorLengthFailWithoutNativeProcessing() {
        assertNull(decodeHeatmaps(FloatArray(65536)))
        assertThrows(IllegalArgumentException::class.java) { decodeHeatmaps(FloatArray(32)) }
        assertThrows(IllegalArgumentException::class.java) { packLcNetBgr(IntArray(4),FloatBuffer.allocate(12)) }
    }
    @Test fun convexityAndOrderingMatchBenchmarkWithoutAdditionalHeuristics() {
        assertEquals(square,validatedDetection(prediction(square.reversed()),1000,800)!!.corners)
        assertNull(validatedDetection(prediction(listOf(Corner(.1,.1),Corner(.2,.2),Corner(.9,.1),Corner(.9,.9))),1000,800))
        assertNotNull(validatedDetection(prediction(listOf(Corner(.1,.1),Corner(.15,.1),Corner(.15,.15),Corner(.1,.15))),1000,800))
        assertNotNull(validatedDetection(prediction(listOf(Corner(.1,.1),Corner(.9,.1),Corner(.9,.13),Corner(.1,.13))),1000,800))
    }
    @Test fun missingPrimaryStaysManualAndSmoothingUsesTimeAndResets() {
        assertNull(validatedDetection(null,1000,800))
        assertNull(validatedDetection(prediction(confidence=.1f),1000,800))
        val smoother=CornerSmoother(); assertEquals(square,smoother.update(square,1_000_000_000))
        val shifted=square.map { Corner(it.x+.02,it.y) }
        val smoothed=smoother.update(shifted,1_100_000_000)!!
        assertEquals(.1+.02*(1-kotlin.math.exp(-100.0/180)),smoothed.first().x,.000001)
        assertTrue(smoothed.first().x in .1.. .12)
        assertNull(smoother.update(null,1_200_000_000)); assertEquals(shifted,smoother.update(shifted,1_300_000_000))
        val small=square.map { Corner(it.x*.4,it.y*.4) }; smoother.reset(); smoother.update(small,2_000_000_000)
        val jumped=small.map { Corner(it.x+.3,it.y+.3) }; assertEquals(jumped,smoother.update(jumped,2_100_000_000))
    }
}

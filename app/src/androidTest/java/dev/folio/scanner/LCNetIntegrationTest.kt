package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.processing.*
import org.opencv.android.OpenCVLoader
import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer
import java.io.File
import org.json.JSONArray

class LCNetIntegrationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun photo(name: String)=InstrumentationRegistry.getInstrumentation().context.assets.open("detection/$name").use { BitmapFactory.decodeStream(it) }
    @Test fun preprocessingMatchesBenchmarkStretchBgrAndLeavesOriginalUnchanged() {
        val image=Bitmap.createBitmap(800,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255,64,32)) }
        val target=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        try {
            val input=FloatBuffer.allocate(196608); preprocessLCNet(image,target,IntArray(65536),input)
            for(index in listOf(0,65535,32768)) { assertEquals(32/255f,input[index],.001f); assertEquals(64/255f,input[65536+index],.001f); assertEquals(1f,input[131072+index],.001f) }
            assertEquals(Color.rgb(255,64,32),image.getPixel(0,0)); assertEquals(800,image.width)
        } finally { image.recycle(); target.recycle() }
    }
    @Test fun nativeLargestContourCentroidAndMissingChannelDecodeMatchReference() {
        check(OpenCVLoader.initLocal())
        val values=FloatArray(65536); val centers=listOf(20 to 20,100 to 20,100 to 100,20 to 100)
        centers.forEachIndexed { c,(x,y) -> for(j in y-3..y+3) for(i in x-3..x+3) values[c*16384+j*128+i]=.9f }
        // Smaller competing region must not override the proven largest-contour rule.
        for(j in 40..42) for(i in 40..42) values[j*128+i]=.95f
        val result=decodeHeatmaps(values)!!
        result.corners.zip(centers).forEach { (p,q) -> assertEquals((q.first+.5)/128,p.x,.00001); assertEquals((q.second+.5)/128,p.y,.00001) }
        assertEquals(.9f,result.confidence!!,.001f)
        values.fill(0f,3*16384,4*16384); assertNull(decodeHeatmaps(values))
    }
    @Test fun lazyXnnpackSessionReusesBuffersAndProfileProvesActualProviderNodes() {
        val profile=File(context.cacheDir,"lcnet-provider-check")
        val detector=LCNetDocumentDetector(context); detector.enableProfiling(profile)
        assertEquals(0,detector.initializationCount)
        val image=photo("striped-card.jpg")
        try {
            repeat(3) { val result=detector.detect(image); assertNotNull(result); assertEquals(DetectionSource.LCNET,result!!.source); assertTrue(result.inferenceTimeMs>0) }
            assertEquals(1,detector.initializationCount); assertEquals("XNNPACK",detector.diagnostics.provider)
        } finally { image.recycle(); detector.close() }
        val file=context.cacheDir.listFiles()!!.filter { it.name.startsWith("lcnet-provider-check") }.maxBy { it.lastModified() }
        val events=JSONArray(file.readText()); val counts=mutableMapOf<String,Int>()
        for(i in 0 until events.length()) { val ep=events.getJSONObject(i).optJSONObject("args")?.optString("provider") ?: ""; if(ep.isNotEmpty()) counts[ep]=(counts[ep] ?: 0)+1 }
        android.util.Log.i("Folio LCNet test","Executed provider nodes: $counts")
        assertTrue("XNNPACK registration must execute nodes: $counts",counts.any { it.key.contains("Xnnpack",true) && it.value>0 })
        file.delete()
    }
    @Test fun productionLcNetKeepsPhotographicCardCornersAcrossExposureChanges() {
        ImagePipeline(context).use { pipeline ->
            val source=photo("striped-card.jpg")
            val expected=Geometry.order(listOf(Corner(.053,.434),Corner(.696,.151),Corner(.970,.445),Corner(.330,.771)))
            try { for((gain,offset) in listOf(1f to 0f,.65f to 0f,.7f to 55f)) {
                val image=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
                try {
                    Canvas(image).drawBitmap(source,0f,0f,Paint().apply { colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(gain,0f,0f,0f,offset,0f,gain,0f,0f,offset,0f,0f,gain,0f,offset,0f,0f,0f,1f,0f))) })
                    val result=requireNotNull(pipeline.detectResult(image))
                    assertEquals(DetectionSource.LCNET,result.source)
                    assertTrue(result.corners.indices.all { result.corners[it].distance(expected[it])<.065 })
                    val output=pipeline.correct(image,result.corners)
                    try { assertTrue(output.width>500 && output.height>500) } finally { output.recycle() }
                } finally { image.recycle() }
            } } finally { source.recycle() }
        }
    }
    @Test fun productionSeamUsesOnlyLcNetAndMissesRemainManual() {
        ImagePipeline(context).use { pipeline ->
            val card=photo("striped-card.jpg")
            try { val result=pipeline.detectResult(card)!!; assertEquals(DetectionSource.LCNET,result.source); val warp=pipeline.correct(card,result.corners); try { assertTrue(warp.width>1000 && warp.height>1000) } finally { warp.recycle() } } finally { card.recycle() }
            val table=photo("table-paper.jpg")
            try { assertNull("Benchmark also misses this photo; no hidden image-edge fallback is allowed",pipeline.detectResult(table)) } finally { table.recycle() }
            val blank=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try { assertNull(pipeline.detectResult(blank)) } finally { blank.recycle() }
        }
    }
}

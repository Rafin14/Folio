package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.folio.scanner.processing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
// Frozen historical detector + current native warp: original assertions remain unchanged.
class ScanProcessingTest {
    @Test fun detectAndCorrectPaperUsingNativeOpenCv() {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); canvas.drawColor(Color.DKGRAY)
            canvas.drawRect(100f, 150f, 700f, 850f, Paint().apply { color = Color.WHITE })
            val pipeline = ImagePipeline(object: DocumentCornerDetector { override fun detect(image: Bitmap): DocumentDetectionResult? { check(org.opencv.android.OpenCVLoader.initLocal()); return LegacyOpenCvDetector.detect(image)?.let { DocumentDetectionResult(it,null,0.0,DetectionSource.MANUAL) } } })
            val corners = requireNotNull(pipeline.detect(bitmap))
            assertTrue(Geometry.area(corners) > .45)
            val output = pipeline.correct(bitmap, corners)
            try { assertTrue(output.width in 580..620); assertTrue(output.height in 680..720); assertEquals(Color.WHITE, output.getPixel(output.width / 2, output.height / 2)) }
            finally { output.recycle() }
        } finally { bitmap.recycle() }
    }
}

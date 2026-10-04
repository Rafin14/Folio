package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.processing.*
import org.junit.Assert.*
import org.junit.Test

// Retains all historical OpenCV accuracy assertions against a test-only frozen baseline.
// LCNet-only production behavior is independently checked by LCNetIntegrationTest.
class DetectionTest {
    @Test fun capturedPapersAndExposureVariantsKeepContentInsideCrop() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val pipeline = ImagePipeline(object: DocumentCornerDetector { override fun detect(image: Bitmap): DocumentDetectionResult? { check(org.opencv.android.OpenCVLoader.initLocal()); return LegacyOpenCvDetector.detect(image)?.let { DocumentDetectionResult(it,null,0.0,DetectionSource.MANUAL) } } })
        val fixtures = listOf(
            "table-paper.jpg" to listOf(Corner(.05,.034),Corner(.923,.03),Corner(.917,.729),Corner(.034,.724)),
            "striped-card.jpg" to listOf(Corner(.053,.434),Corner(.696,.151),Corner(.970,.445),Corner(.330,.771))
        )
        fixtures.forEach { (name, expected) ->
            val source = assets.open("detection/$name").use { BitmapFactory.decodeStream(it) }
            try {
                for ((index, values) in listOf(1f to 0f, .65f to 0f, .7f to 55f).withIndex()) {
                    val image = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    try {
                        val (gain, offset) = values
                        Canvas(image).drawBitmap(source,0f,0f,Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(gain,0f,0f,0f,offset, 0f,gain,0f,0f,offset, 0f,0f,gain,0f,offset, 0f,0f,0f,1f,0f))) })
                        val start = android.os.SystemClock.elapsedRealtime()
                        val corners = pipeline.detect(image)
                        android.util.Log.i("Folio detection test", "$name variant $index ${android.os.SystemClock.elapsedRealtime()-start} ms: $corners")
                        assertNotNull("$name exposure $index", corners)
                        val reference = Geometry.order(expected)
                        assertTrue("$name $index corners $corners", corners!!.indices.all { corners[it].distance(reference[it]) < .065 })
                        val cropped = pipeline.correct(image, corners)
                        try { assertTrue(cropped.width > 500 && cropped.height > 500) } finally { cropped.recycle() }
                    } finally { image.recycle() }
                }
            } finally { source.recycle() }
        }
    }
    @Test fun syntheticLightingColorClutterAndInvalidFrames() {
        val pipeline = ImagePipeline(object: DocumentCornerDetector { override fun detect(image: Bitmap): DocumentDetectionResult? { check(org.opencv.android.OpenCVLoader.initLocal()); return LegacyOpenCvDetector.detect(image)?.let { DocumentDetectionResult(it,null,0.0,DetectionSource.MANUAL) } } })
        for ((paper,background) in listOf(Color.WHITE to Color.DKGRAY, Color.rgb(245,245,245) to Color.rgb(215,215,215), Color.rgb(185,215,240) to Color.rgb(100,80,50))) {
            val image = Bitmap.createBitmap(900,1200,Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image); canvas.drawColor(background)
                canvas.drawCircle(75f,100f,60f,Paint().apply { color = Color.RED })
                val path = Path().apply { moveTo(220f,210f); lineTo(725f,250f); lineTo(690f,1010f); lineTo(175f,940f); close() }
                canvas.drawPath(path, Paint().apply { color = paper })
                for (y in 330..850 step 45) canvas.drawText("Document text",275f,y.toFloat(),Paint().apply { color=Color.DKGRAY; textSize=27f })
                val corners = pipeline.detect(image); assertNotNull(corners)
                assertTrue(Geometry.area(corners!!) in .32.. .42)
            } finally { image.recycle() }
        }
        val blank = Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        try { blank.eraseColor(Color.WHITE); assertNull(pipeline.detect(blank)) } finally { blank.recycle() }
    }
    @Test fun noShadowNormalizesUnevenPaperWithoutErasingText() {
        val image = Bitmap.createBitmap(600,800,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawRect(0f,0f,600f,800f,Paint().apply { shader = LinearGradient(0f,0f,600f,0f,Color.rgb(100,100,100),Color.WHITE,Shader.TileMode.CLAMP) })
            canvas.drawText("Retained text",60f,250f,Paint().apply { color=Color.BLACK; textSize=48f })
            val result = ImagePipeline(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext).enhance(image, Enhancement("No Shadow"),0)
            try {
                val before = kotlin.math.abs(Color.red(image.getPixel(50,600))-Color.red(image.getPixel(550,600)))
                val after = kotlin.math.abs(Color.red(result.getPixel(50,600))-Color.red(result.getPixel(550,600)))
                assertTrue(after < before / 3)
                assertTrue((80..350).any { Color.red(result.getPixel(it,230)) < 80 })
            } finally { result.recycle() }
        } finally { image.recycle() }
    }
}

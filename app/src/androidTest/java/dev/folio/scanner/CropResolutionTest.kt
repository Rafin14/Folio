package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.processing.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CropResolutionTest {
    @Test fun retainedCropKeepsResolutionAndAllExifOrientations() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File.createTempFile("crop-resolution", ".jpg", context.cacheDir)
        val pipeline=ImagePipeline(object: DocumentCornerDetector { override fun detect(image: Bitmap): DocumentDetectionResult?=null })
        val corners=listOf(Corner(.25,.25),Corner(.75,.25),Corner(.75,.75),Corner(.25,.75))
        val source=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(source)
        val colors=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        colors.forEachIndexed { i,color -> canvas.drawRect((i%2)*2000f,(i/2)*1500f,(i%2+1)*2000f,(i/2+1)*1500f,Paint().apply { this.color=color }) }
        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG,95,it)) }; source.recycle()
        try {
            for(orientation in 1..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
                val bytes=file.readBytes()
                val full=pipeline.decode(file)
                val reference=try { pipeline.correct(full,corners) } finally { full.recycle() }
                val region=pipeline.correct(file,corners,3200)
                val thumbnail=pipeline.decode(file,1400)
                val old=try { pipeline.correct(thumbnail,corners) } finally { thumbnail.recycle() }
                try {
                    assertTrue("orientation $orientation must retain crop detail",region.width>old.width*3)
                    assertTrue(kotlin.math.abs(reference.width-region.width)<=2)
                    assertTrue(kotlin.math.abs(reference.height-region.height)<=2)
                    for(x in listOf(.2,.8)) for(y in listOf(.2,.8)) {
                        val expected=reference.getPixel((reference.width*x).toInt(),(reference.height*y).toInt())
                        val actual=region.getPixel((region.width*x).toInt(),(region.height*y).toInt())
                        assertTrue("EXIF $orientation pixel",kotlin.math.abs(Color.red(expected)-Color.red(actual))<=5 && kotlin.math.abs(Color.green(expected)-Color.green(actual))<=5 && kotlin.math.abs(Color.blue(expected)-Color.blue(actual))<=5)
                    }
                    assertArrayEquals(bytes,file.readBytes())
                } finally { reference.recycle(); region.recycle(); old.recycle() }
            }
        } finally { file.delete(); pipeline.close() }
    }
}

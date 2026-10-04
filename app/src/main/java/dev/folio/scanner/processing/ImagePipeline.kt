package dev.folio.scanner.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class ImagePipeline internal constructor(private val automatic: DocumentCornerDetector) : AutoCloseable {
    @Inject constructor(automatic: LCNetDocumentDetector) : this(automatic as DocumentCornerDetector)
    constructor(context: android.content.Context) : this(LCNetDocumentDetector(context.applicationContext))
    @Volatile var diagnostics = DetectionDiagnostics(); private set
    @Synchronized fun detectResult(bitmap: Bitmap): DocumentDetectionResult? {
        val begin=android.os.SystemClock.elapsedRealtimeNanos()
        val result=validatedDetection(automatic.detect(bitmap),bitmap.width,bitmap.height)
        if(dev.folio.scanner.BuildConfig.DEBUG) android.util.Log.d(LCNetDocumentDetector.TAG,"Detection source=${result?.source} input=${bitmap.width}x${bitmap.height} corners=${result?.corners} confidence=${result?.confidence}")
        diagnostics=automatic.diagnostics.copy(totalMs=(android.os.SystemClock.elapsedRealtimeNanos()-begin)/1e6,source=result?.source,confidence=result?.confidence,corners=result?.corners)
        return result
    }
    override fun close()=automatic.close()
    private fun ready() = check(OpenCVLoader.initLocal()) { "Image processing could not start. Reopen Folio and retry." }
    fun decode(file: File, limit: Int = 8192): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image cannot be opened. Choose a JPEG, PNG, or supported HEIC image." }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            // ponytail: retain ordinary 12 MP captures; larger originals stay on disk and use a bounded decode.
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > limit || bounds.outWidth.toLong() * bounds.outHeight / inSampleSize / inSampleSize > 12_500_000) inSampleSize *= 2
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Could not decode image." }
        val exif = ExifInterface(file)
        val matrix = Matrix().apply { if (exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }
    fun detect(bitmap: Bitmap): List<Corner>? {
        return detectResult(bitmap)?.corners
    }
    /** Decode the retained crop region, not a thumbnail of the entire camera frame. */
    @Suppress("DEPRECATION") // BitmapRegionDecoder's compatible overload supports Android 8 onward.
    fun correct(file: File, points: List<Corner>, limit: Int = 8192): Bitmap {
        require(limit >= 2 && Geometry.valid(points))
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        require(bounds.outWidth>=2 && bounds.outHeight>=2) { "This image cannot be opened." }
        val exif=ExifInterface(file)
        val orientation=Matrix().apply { if(exif.isFlipped) postScale(-1f,1f); postRotate(exif.rotationDegrees.toFloat()) }
        val frame=android.graphics.RectF(0f,0f,(bounds.outWidth-1).toFloat(),(bounds.outHeight-1).toFloat())
        orientation.mapRect(frame); orientation.postTranslate(-frame.left,-frame.top)
        val uprightWidth=frame.width()+1; val uprightHeight=frame.height()+1
        val xy=points.flatMap { listOf((it.x*(uprightWidth-1)).toFloat(),(it.y*(uprightHeight-1)).toFloat()) }.toFloatArray()
        val inverse=Matrix(); check(orientation.invert(inverse)); inverse.mapPoints(xy)
        val region=android.graphics.Rect(
            (kotlin.math.floor(xy.filterIndexed { i,_ -> i%2==0 }.min()).toInt()-4).coerceAtLeast(0),
            (kotlin.math.floor(xy.filterIndexed { i,_ -> i%2==1 }.min()).toInt()-4).coerceAtLeast(0),
            (kotlin.math.ceil(xy.filterIndexed { i,_ -> i%2==0 }.max()).toInt()+5).coerceAtMost(bounds.outWidth),
            (kotlin.math.ceil(xy.filterIndexed { i,_ -> i%2==1 }.max()).toInt()+5).coerceAtMost(bounds.outHeight))
        val output=Geometry.output(points,uprightWidth.toInt(),uprightHeight.toInt())
        val options=BitmapFactory.Options().apply {
            inPreferredConfig=Bitmap.Config.ARGB_8888; inSampleSize=1
            while(max(output.first,output.second)/inSampleSize>limit ||
                region.width().toLong()*region.height()/inSampleSize/inSampleSize>12_500_000 ||
                output.first.toLong()*output.second/inSampleSize/inSampleSize>12_500_000) inSampleSize*=2
        }
        val decoder=try { BitmapRegionDecoder.newInstance(file.path,false) } catch(_: java.io.IOException) { null }
        if(decoder==null) {
            val image=decode(file,limit)
            return try { correct(image,points) } finally { image.recycle() }
        }
        val decoded=try { requireNotNull(decoder.decodeRegion(region,options)) { "Could not decode document region." } } finally { decoder.recycle() }
        val image=if(orientation.isIdentity) decoded else try {
            Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,Matrix().apply { if(exif.isFlipped) postScale(-1f,1f); postRotate(exif.rotationDegrees.toFloat()) },true)
        } catch(failure: Throwable) { decoded.recycle(); throw failure }
        if(image!==decoded) decoded.recycle()
        val crop=android.graphics.RectF(region.left.toFloat(),region.top.toFloat(),(region.right-1).toFloat(),(region.bottom-1).toFloat())
        orientation.mapRect(crop)
        val local=points.map { Corner(((it.x*(uprightWidth-1)-crop.left)/crop.width()).coerceIn(0.0,1.0),((it.y*(uprightHeight-1)-crop.top)/crop.height()).coerceIn(0.0,1.0)) }
        return try { correct(image,local) } finally { image.recycle() }
    }
    fun correct(bitmap: Bitmap, points: List<Corner>): Bitmap {
        ready()
        val (width, height) = Geometry.output(points, bitmap.width, bitmap.height)
        val source = Mat(); val output = Mat()
        val from = MatOfPoint2f(*points.map { Point(it.x * (bitmap.width - 1), it.y * (bitmap.height - 1)) }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point((width - 1).toDouble(), 0.0), Point((width - 1).toDouble(), (height - 1).toDouble()), Point(0.0, (height - 1).toDouble()))
        var transform: Mat? = null
        try {
            Utils.bitmapToMat(bitmap, source)
            transform = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(source, output, transform, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC)
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally { source.release(); output.release(); from.release(); to.release(); transform?.release() }
    }
    /** White paper canvas: isotropic scale, never stretch the retained scan. */
    fun pageCanvas(bitmap: Bitmap, layout: dev.folio.scanner.data.PageLayout): Bitmap {
        if (layout.dimensions == null) return bitmap
        val (width,height) = layout.canvas(bitmap.width,bitmap.height)
        val result = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(result); canvas.drawColor(android.graphics.Color.WHITE)
            val sx = width.toFloat()/bitmap.width; val sy = height.toFloat()/bitmap.height
            val scale = if(layout.fit == "Fit") minOf(sx,sy) else maxOf(sx,sy)
            val w=bitmap.width*scale; val h=bitmap.height*scale
            canvas.drawBitmap(bitmap,null,android.graphics.RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return result
        } catch(failure:Throwable) { result.recycle(); throw failure }
    }
    fun enhance(bitmap: Bitmap, settings: Enhancement, rotation: Int): Bitmap {
        ready()
        require(rotation in listOf(0, 90, 180, 270))
        val image = Mat(); val gray = Mat(); val background = Mat(); val blurred = Mat()
        try {
            Utils.bitmapToMat(bitmap, image)
            Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2RGB)
            val shadow = maxOf(settings.shadow, if (settings.preset in listOf("Auto", "Document", "No Shadow")) 1.0 else 0.0)
            if (shadow > 0.0) {
                // ponytail: low-frequency normalization reduces shadows; curved book pages need a separate dewarping model.
                val size = Size(maxOf(2, image.cols() / 32).toDouble(), maxOf(2, image.rows() / 32).toDouble())
                Imgproc.resize(image, background, size)
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
                try { Imgproc.morphologyEx(background, background, Imgproc.MORPH_CLOSE, kernel) } finally { kernel.release() }
                Imgproc.GaussianBlur(background, background, Size(5.0, 5.0), 0.0)
                Imgproc.resize(background, background, image.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
                Core.add(background, Scalar(1.0, 1.0, 1.0), background)
                Core.divide(image, background, blurred, 245.0)
                Core.addWeighted(image, 1.0 - shadow, blurred, shadow, 0.0, image)
            }
            if (settings.preset in listOf("Grayscale", "Black & White", "Document")) {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
                if (settings.preset == "Black & White" || settings.preset == "Document") {
                    Imgproc.adaptiveThreshold(gray, gray, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 31, settings.threshold)
                }
                Imgproc.cvtColor(gray, image, Imgproc.COLOR_GRAY2RGB)
            } else if (settings.saturation != 1.0 || settings.preset == "Color") {
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
                Imgproc.cvtColor(gray, gray, Imgproc.COLOR_GRAY2RGB)
                val saturation = settings.saturation * if (settings.preset == "Color") 1.15 else 1.0
                Core.addWeighted(image, saturation, gray, 1.0 - saturation, 0.0, image)
            }
            image.convertTo(image, -1, settings.contrast, settings.brightness + if (settings.preset == "Lighten") 24.0 else 0.0)
            val sharpness = maxOf(settings.sharpness, if (settings.preset == "Sharpen") .8 else 0.0)
            if (sharpness > 0) {
                Imgproc.GaussianBlur(image, blurred, Size(0.0, 0.0), 1.2)
                Core.addWeighted(image, 1 + sharpness, blurred, -sharpness, 0.0, image)
            }
            when (rotation) { 90 -> Core.rotate(image, image, Core.ROTATE_90_CLOCKWISE); 180 -> Core.rotate(image, image, Core.ROTATE_180); 270 -> Core.rotate(image, image, Core.ROTATE_90_COUNTERCLOCKWISE) }
            Imgproc.cvtColor(image, image, Imgproc.COLOR_RGB2RGBA)
            return Bitmap.createBitmap(image.cols(), image.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(image, it) }
        } finally { image.release(); gray.release(); background.release(); blurred.release() }
    }
}

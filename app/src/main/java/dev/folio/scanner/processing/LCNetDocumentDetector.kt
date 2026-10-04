package dev.folio.scanner.processing

import android.content.Context
import android.graphics.*
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.*
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped, lazy session. Shared scratch/tensor storage is serialized across callers. */
@Singleton
class LCNetDocumentDetector @Inject constructor(@ApplicationContext private val context: Context): DocumentCornerDetector {
    private var engine: Engine?=null
    private var unavailable=false
    private var closed=false
    private var profile: File?=null
    @Volatile override var diagnostics=DetectionDiagnostics(); private set
    internal var initializationCount=0; private set
    @Synchronized internal fun enableProfiling(path: File) { check(engine==null && !unavailable); profile=path }
    @Synchronized override fun detect(image: Bitmap): DocumentDetectionResult? {
        check(!closed) { "Detector is closed." }
        if(unavailable) return null
        val begin=SystemClock.elapsedRealtimeNanos()
        try {
            if(engine==null) {
                initializationCount++
                engine=Engine(context,profile)
                diagnostics=diagnostics.copy(provider=engine!!.provider,loadMs=(SystemClock.elapsedRealtimeNanos()-begin)/1e6)
                Log.i(TAG,"LCNet initialized; Execution provider: ${engine!!.provider}; Input size: img FLOAT [1,3,256,256]; Output tensors: heatmap FLOAT [1,4,128,128]; load=${diagnostics.loadMs} ms")
            }
            val result=engine!!.detect(image)
            diagnostics=diagnostics.copy(inferenceMs=engine!!.inferenceMs,totalMs=(SystemClock.elapsedRealtimeNanos()-begin)/1e6,
                source=result?.source,confidence=result?.confidence,corners=result?.corners,frames=diagnostics.frames+1,memoryMb=if(dev.folio.scanner.BuildConfig.DEBUG) heapMb() else 0.0)
            return result
        } catch(failure: Exception) {
            unavailable=true; engine?.close(); engine=null
            Log.e(TAG,"LCNet unavailable; manual corner adjustment required for this process",failure)
            diagnostics=diagnostics.copy(provider="Unavailable",totalMs=(SystemClock.elapsedRealtimeNanos()-begin)/1e6)
            return null
        } catch(failure: LinkageError) {
            unavailable=true
            Log.e(TAG,"ONNX native runtime unavailable on this device; manual corner adjustment required",failure)
            diagnostics=diagnostics.copy(provider="Unavailable")
            return null
        }
    }
    @Synchronized override fun close() { if(!closed) { closed=true; engine?.close(); engine=null } }

    private class Engine(context: Context,profile: File?): AutoCloseable {
        val provider: String
        private val session: OrtSession
        private val target: Bitmap
        private val pixels=IntArray(65536)
        private val input=ByteBuffer.allocateDirect(196608*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val output=FloatArray(65536)
        private val canvas by lazy { Canvas(target) }
        private val rect=android.graphics.Rect(0,0,256,256)
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        var inferenceMs=0.0; private set
        init {
            check(OpenCVLoader.initLocal()) { "OpenCV decoder could not initialize." }
            val environment=OrtEnvironment.getEnvironment()
            val bytes=context.assets.open("models/lcnet100_h_e_bifpn_256_fp32.onnx").use { it.readBytes() }
            Log.i(TAG,"Available execution providers: ${OrtEnvironment.getAvailableProviders()}; requesting XNNPACK")
            fun create(xnnpack: Boolean)=OrtSession.SessionOptions().use { options ->
                // Match the physically validated benchmark configuration; tune only with device evidence.
                options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
                if(xnnpack) options.addXnnpack(mapOf("intra_op_num_threads" to "2"))
                profile?.let { options.enableProfiling(it.path) }
                environment.createSession(bytes,options)
            }
            val initialized=initializeLcNetProvider(::create) { failure ->
                Log.w(TAG,"XNNPACK initialization failed; explicitly retrying with CPU execution provider",failure)
            }
            provider=initialized.first; session=initialized.second
            try {
                val a=session.inputInfo.getValue("img").info as TensorInfo
                val b=session.outputInfo.getValue("heatmap").info as TensorInfo
                check(a.type==OnnxJavaType.FLOAT && a.shape.contentEquals(longArrayOf(1,3,256,256))) { "Unsupported LCNet input $a" }
                check(b.type==OnnxJavaType.FLOAT && b.shape.contentEquals(longArrayOf(1,4,128,128))) { "Unsupported LCNet output $b" }
                target=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
            } catch(failure: Throwable) { session.close(); throw failure }
        }
        fun detect(image: Bitmap): DocumentDetectionResult? {
            canvas.drawBitmap(image,null,rect,paint)
            target.getPixels(pixels,0,256,0,0,256,256); packLcNetBgr(pixels,input)
            val begin=SystemClock.elapsedRealtimeNanos()
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),input,longArrayOf(1,3,256,256)).use { tensor ->
                session.run(mapOf("img" to tensor)).use { result ->
                    inferenceMs=(SystemClock.elapsedRealtimeNanos()-begin)/1e6
                    val buffer=(result.get("heatmap").get() as OnnxTensor).floatBuffer
                    check(buffer.remaining()==65536) { "Unexpected LCNet heatmap size" }; buffer.get(output)
                    return decodeHeatmaps(output)?.copy(inferenceTimeMs=inferenceMs)
                }
            }
        }
        override fun close() {
            try { val path=session.endProfiling(); if(path.isNotEmpty()) Log.i(TAG,"Execution-provider profile: $path") }
            finally { session.close(); target.recycle() }
        }
    }
    companion object { const val TAG="Folio LCNet" }
}
fun heapMb(): Double { val r=Runtime.getRuntime(); return (r.totalMemory()-r.freeMemory()+Debug.getNativeHeapAllocatedSize())/1048576.0 }
/** Benchmark preprocessing unchanged: upright BGR, direct stretch, CHW float32 /255. */
fun preprocessLCNet(image: Bitmap,target: Bitmap,pixels: IntArray,input: FloatBuffer) {
    require(target.width==256 && target.height==256)
    Canvas(target).drawBitmap(image,null,android.graphics.Rect(0,0,256,256),Paint(Paint.FILTER_BITMAP_FLAG))
    target.getPixels(pixels,0,256,0,0,256,256); packLcNetBgr(pixels,input)
}
/** Benchmark decoder: largest external thresholded contour, geometric centroid, pixel centers. */
fun decodeHeatmaps(values: FloatArray,threshold: Float=.3f): DocumentDetectionResult? {
    require(values.size==65536 && threshold in 0f..1f)
    val centers=mutableListOf<Corner>(); val peaks=mutableListOf<Float>()
    for(channel in 0..3) {
        val offset=channel*16384
        // Reject missing channels before native allocation (also exercises blank decoding on JVM).
        if((0 until 16384).none { values[offset+it].isFinite() && values[offset+it]>=threshold }) return null
        val mask=Mat(128,128,CvType.CV_8UC1); val hierarchy=Mat(); val contours=mutableListOf<MatOfPoint>()
        try {
            val bytes=ByteArray(16384) { i -> if(values[offset+i].isFinite() && values[offset+i]>=threshold) 255.toByte() else 0 }
            mask.put(0,0,bytes); Imgproc.findContours(mask,contours,hierarchy,Imgproc.RETR_EXTERNAL,Imgproc.CHAIN_APPROX_SIMPLE)
            val largest=contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val moments=Imgproc.moments(largest); if(moments.m00<=0) return null
            centers+=Corner(moments.m10/moments.m00,moments.m01/moments.m00)
            peaks+=(0 until 16384).maxOf { i -> values[offset+i].takeIf { it.isFinite() } ?: 0f }
        } finally { mask.release(); hierarchy.release(); contours.forEach { it.release() } }
    }
    return decodedHeatmapCorners(centers,peaks)
}

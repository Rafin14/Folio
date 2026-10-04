package dev.folio.scanner

import android.graphics.*
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.camera.view.PreviewView
import dev.folio.scanner.processing.*
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class LCNetBenchmarkTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun samePhotoReferenceAndProductionTimingsAndHeadingLimitation() {
        check(OpenCVLoader.initLocal())
        Log.i("FolioLCNetCSV","image,method,found,source,confidence,inference_ms,total_ms,heap_mb")
        LCNetDocumentDetector(context).use { detector ->
            ImagePipeline(detector).use { pipeline ->
                for(name in listOf("table-paper.jpg","striped-card.jpg")) {
                    val image=instrumentation.context.assets.open("detection/$name").use { BitmapFactory.decodeStream(it) }
                    try {
                        detector.detect(image); val loadMs=detector.diagnostics.loadMs
                        Log.i("Folio LCNet benchmark","model load=$loadMs ms; provider=${detector.diagnostics.provider}; process heap=${heapMb()} MB")
                        val times=mutableMapOf<String,MutableList<Double>>(); var rows=emptyList<String>()
                        repeat(5) {
                            fun measure(name: String,run: ()->DocumentDetectionResult?): String {
                                val start=SystemClock.elapsedRealtimeNanos(); val result=run(); val time=(SystemClock.elapsedRealtimeNanos()-start)/1e6
                                times.getOrPut(name) { mutableListOf() }+=time
                                return "$name,${result!=null},${result?.source ?: "MANUAL"},${result?.confidence ?: ""},${result?.inferenceTimeMs ?: ""},TOTAL,${heapMb()}"
                            }
                            rows=listOf(measure("OPENCV") { LegacyOpenCvDetector.detect(image)?.let { DocumentDetectionResult(it,null,0.0,DetectionSource.MANUAL) } },
                                measure("LCNET_ONLY") { validatedDetection(detector.detect(image),image.width,image.height) },measure("PRODUCTION") { pipeline.detectResult(image) })
                        }
                        rows.forEach { row -> val method=row.substringBefore(','); Log.i("FolioLCNetCSV","$name,"+row.replace("TOTAL",times.getValue(method).sorted()[2].toString())) }
                    } finally { image.recycle() }
                }
                val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
                val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE); canvas.drawText("Local document",25f,100f,Paint().apply { color=Color.BLUE; textSize=24f })
                val bytes=java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it); it.toByteArray() }; bitmap.recycle()
                val decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                try {
                    val raw=detector.detect(decoded)
                    Log.i("Folio LCNet benchmark","Printed page raw=$raw area=${raw?.let { Geometry.area(it.corners) }} dimensions=${raw?.let { Geometry.output(it.corners,400,600) }}")
                    val result=pipeline.detectResult(decoded)
                    Log.i("Folio LCNet benchmark","Printed page selected=$result")
                    assertNotNull("Record benchmark heading false positive without a hidden production heuristic",result)
                    assertEquals(raw!!.corners,result!!.corners)
                } finally { decoded.recycle() }
            }
        }
    }
    @Test fun cameraAnalysisRateOnConnectedDevice() {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant dev.folio.scanner android.permission.CAMERA")).use { it.readBytes() }
        check(OpenCVLoader.initLocal())
        Log.i("FolioLiveCSV","method,window_seconds,completed_frames,fps,mean_detection_ms,heap_mb")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(method in listOf("OPENCV","LCNET_PRODUCTION")) {
                val baseline=object: DocumentCornerDetector {
                    override fun detect(image: Bitmap)=LegacyOpenCvDetector.detect(image)?.let { DocumentDetectionResult(it,null,0.0,DetectionSource.MANUAL) }
                }
                val pipeline=if(method=="OPENCV") ImagePipeline(baseline) else ImagePipeline(context)
                val scanner=CameraScanner(context,pipeline); val warm=CountDownLatch(4)
                val ticks=CopyOnWriteArrayList<Long>(); val costs=CopyOnWriteArrayList<Double>(); val failure=AtomicReference<String?>()
                scenario.onActivity { activity ->
                    val view=PreviewView(activity).apply { scaleType=PreviewView.ScaleType.FIT_CENTER }
                    activity.setContentView(view)
                    scanner.bind(view,activity,false,{ _,_,_ -> if(warm.count>0) warm.countDown() else { ticks+=SystemClock.elapsedRealtimeNanos(); costs+=pipeline.diagnostics.totalMs } },{ failure.set(it) })
                }
                try {
                    assertTrue("Camera analysis did not warm: ${failure.get()}",warm.await(30,TimeUnit.SECONDS))
                    // Software-rendered emulator scene delivery is not a physical-camera FPS gate.
                    val window=if(android.os.Build.HARDWARE in listOf("ranchu","goldfish")) 15000L else 5000L
                    val start=SystemClock.elapsedRealtimeNanos(); Thread.sleep(window); val seconds=(SystemClock.elapsedRealtimeNanos()-start)/1e9
                    assertNull(failure.get()); assertTrue("$method delivered ${ticks.size} frames in $seconds seconds",ticks.size>=3)
                    Log.i("FolioLiveCSV","$method,$seconds,${ticks.size},${ticks.size/seconds},${costs.average()},${heapMb()}")
                } finally { scenario.onActivity { scanner.close() }; pipeline.close() }
            }
        }
    }
}

package dev.folio.scanner.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

class CameraScanner(private val context: Context, private val pipeline: ImagePipeline) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    @Volatile private var closed = false
    @Volatile private var generation = 0
    @Volatile var fps = 0.0; private set
    fun bind(view: PreviewView, owner: LifecycleOwner, front: Boolean, boundary: (List<Corner>?, Float, Boolean) -> Unit, failure: (String) -> Unit) {
        val ticket = ++generation
        // CameraX computes the common crop from the measured preview, including display rotation.
        view.doOnLayout {
            if (closed || generation != ticket) return@doOnLayout
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (closed || generation != ticket) return@addListener
                try {
                    val provider = future.get().also { this.provider = it }
                    provider.unbindAll()
                    val rotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                    val preview = Preview.Builder().setTargetRotation(rotation).build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val capture = ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build().also { this.capture = it }
                    val analysis = ImageAnalysis.Builder().setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(android.util.Size(1280,960),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                    val smoother = CornerSmoother()
                    var nextInference = 0L; var previous: List<Corner>? = null; var stableSince = 0L
                    var reportedError = false; var started = 0L; var frames = 0; var lastAspect = 0f
                    analysis.setAnalyzer(executor) { image ->
                        var bitmap: Bitmap? = null; var cropped: Bitmap? = null; var upright: Bitmap? = null
                        try {
                            val now = SystemClock.elapsedRealtimeNanos()
                            if (closed || generation != ticket || now < nextInference) return@setAnalyzer
                            nextInference = now + 100_000_000L // 10 FPS ceiling; KEEP_ONLY_LATEST drops stale frames.
                            bitmap = image.toBitmap()
                            val rect = image.cropRect
                            cropped = Bitmap.createBitmap(bitmap,rect.left,rect.top,rect.width(),rect.height())
                            upright = if (image.imageInfo.rotationDegrees == 0) cropped else Bitmap.createBitmap(cropped,0,0,cropped.width,cropped.height,Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) },true)
                            val aspect = upright.width.toFloat()/upright.height
                            if (aspect != lastAspect) { smoother.reset(); previous = null; lastAspect = aspect }
                            val detected = pipeline.detect(upright)
                            val old = previous
                            if (detected == null || old == null || detected.indices.any { detected[it].distance(old[it]) >= .025 }) stableSince = now
                            val complete = SystemClock.elapsedRealtimeNanos()
                            val smoothed = smoother.update(detected,complete)
                            previous = smoothed
                            if (started == 0L) started = complete
                            frames++; fps = if (frames > 1) (frames-1)*1e9/(complete-started) else 0.0
                            if (!reportedError && pipeline.diagnostics.provider == "Unavailable") {
                                reportedError = true
                                main.execute { if (!closed && ticket == generation) failure("Live detection is unavailable. Capture manually and adjust the corners.") }
                            }
                            main.execute { if (!closed && ticket == generation) boundary(smoothed,aspect,detected != null && now-stableSince > 1_400_000_000L) }
                        } catch (error: Exception) {
                            android.util.Log.e("Folio camera","Frame analysis failed",error)
                            if (!reportedError) { reportedError = true; main.execute { if (!closed && ticket == generation) failure("Live detection is unavailable. Capture manually and adjust the corners.") } }
                        } finally {
                            if (upright !== cropped) upright?.recycle()
                            if (cropped !== bitmap) cropped?.recycle()
                            bitmap?.recycle(); image.close()
                        }
                    }
                    val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(capture).addUseCase(analysis)
                        .setViewPort(requireNotNull(view.viewPort) { "Preview must be measured before camera binding." }).build()
                    camera = provider.bindToLifecycle(owner,if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,group)
                } catch (error: Exception) { android.util.Log.e("Folio camera","Binding failed",error); failure("Camera unavailable. Try the other camera or import photos.") }
            },main)
        }
    }
    fun torch(enabled: Boolean, failure: (String) -> Unit) {
        val current = camera ?: return
        if (!current.cameraInfo.hasFlashUnit()) { failure("This camera has no torch."); return }
        current.cameraControl.enableTorch(enabled)
    }
    fun capture(file: File, rotation: Int, done: (File) -> Unit, failure: (String) -> Unit) {
        val current = capture ?: run { failure("Camera is still starting. Try again."); return }
        current.targetRotation = rotation
        current.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(),executor,object: ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) { main.execute { done(file) } }
            override fun onError(exception: ImageCaptureException) { file.delete(); main.execute { failure("Capture failed. Check available storage and try again.") } }
        })
    }
    fun close() { closed = true; generation++; provider?.unbindAll(); executor.shutdown() }
}

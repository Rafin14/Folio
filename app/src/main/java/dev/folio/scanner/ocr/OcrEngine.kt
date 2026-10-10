package dev.folio.scanner.ocr

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.preprocess.*
import com.paddle.ocr.postprocess.*
import com.paddle.ocr.util.BitmapUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.opencv.android.OpenCVLoader
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

data class OcrMeasurement(val regions:List<TextRegion>,val initializationMs:Long,val preprocessMs:Long,val detectionMs:Long,val recognitionMs:Long,val postprocessMs:Long,val totalMs:Long,val detectorWidth:Int,val detectorHeight:Int,val provider:String="CPUExecutionProvider",val cpuFallback:Boolean=false,val detectionProvider:String="unverified",val recognitionProvider:String="unverified",val availableProviders:String="",val fallbackReason:String="")
@Singleton
class OcrEngine @Inject constructor(@ApplicationContext private val context:Context) : AutoCloseable {
    private val env by lazy { OrtEnvironment.getEnvironment() }
    private var det:OrtSession?=null; private var rec:OrtSession?=null
    private var retainedOptions:OrtSession.SessionOptions?=null
    private var characters:List<String> = emptyList()
    private var initializationMs=0L
    internal var forceCpuForBenchmark=false
    internal var probeWebGpuForBenchmark=false
    internal var isolatedHardwareProbe=false
    private var fallback=false
    private var hardware=false
    private var requestedProvider="CPU"
    private val measuredProviders=linkedSetOf<String>()
    private val stageProviders=mutableMapOf<OrtSession,Set<String>>()
    private var fallbackReason=""
    private val profilePending=mutableSetOf<OrtSession>()
    private fun endProfile(session:OrtSession) {
        if(!profilePending.remove(session)) return
        val file=File(session.endProfiling())
        val providers=linkedSetOf<String>()
        try {
            android.util.JsonReader(file.reader()).use { reader ->
                reader.beginArray()
                while(reader.hasNext()) {
                    reader.beginObject()
                    while(reader.hasNext()) { if(reader.nextName()=="args") {
                        reader.beginObject(); while(reader.hasNext()) { if(reader.nextName()=="provider") providers+=reader.nextString() else reader.skipValue() }; reader.endObject()
                    } else reader.skipValue() }
                    reader.endObject()
                }; reader.endArray()
            }
            stageProviders[session]=providers; measuredProviders+=providers
        } finally { file.delete() }
    }
    @Synchronized override fun close() { det?.close(); rec?.close(); det=null; rec=null; retainedOptions?.close(); retainedOptions=null; characters=emptyList(); profilePending.clear(); measuredProviders.clear(); stageProviders.clear() }
    private fun now()=android.os.SystemClock.elapsedRealtime()
    private fun load() {
        if(det!=null && rec!=null) return
        val start=now(); check(OpenCVLoader.initLocal())
        fun model(kind:String):File {
            val file=File(context.noBackupFilesDir,"ocr/$OCR_MODEL/$kind.onnx").apply { parentFile!!.mkdirs() }
            val expected=if(kind=="det") "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e" else "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"
            if(!file.exists() || dev.folio.scanner.backup.hashFile(file)!=expected) {
                val temp=File(file.path+".writing")
                context.assets.open("models/ocr/$kind/inference.onnx").use { input -> temp.outputStream().use { input.copyTo(it,65536) } }
                check(dev.folio.scanner.backup.hashFile(temp)==expected); check(temp.renameTo(file))
            }; return file
        }
        val available=OrtEnvironment.getAvailableProviders()
        // ORT 1.30.0 native WebGPU crashes on the verified CPH2747 during session creation.
        // Keep the diagnostic opt-in debug-only until this model/runtime path is reliable.
        val webGpu=(isolatedHardwareProbe || dev.folio.scanner.BuildConfig.DEBUG && probeWebGpuForBenchmark) && !fallback && !forceCpuForBenchmark && OrtProvider.WEBGPU in available
        hardware=webGpu || !fallback && ocrHardwareEligible(android.os.Build.VERSION.SDK_INT,OrtProvider.NNAPI in OrtEnvironment.getAvailableProviders(),forceCpuForBenchmark)
        if(!hardware && !forceCpuForBenchmark) { fallback=true; fallbackReason="NNAPI unavailable or Android below 29" }
        requestedProvider=if(webGpu) "WebGPU" else if(hardware) "NNAPI" else "CPU"
        fun sessions(accelerate:Boolean) { val options=OrtSession.SessionOptions(); retainedOptions=options
            options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1)
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            if(accelerate && requestedProvider=="WebGPU") {
                options.setMemoryPatternOptimization(false)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                options.addWebGPU(emptyMap())
            }
            else if(accelerate) options.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
            if(dev.folio.scanner.BuildConfig.DEBUG || isolatedHardwareProbe) options.enableProfiling(File(context.cacheDir,"ocr-provider-${java.util.UUID.randomUUID()}").path)
            try { det=env.createSession(model("det").path,options); rec=env.createSession(model("rec").path,options)
                val a=JSONArray(context.assets.open("models/ocr/rec/characters.json").bufferedReader().use { it.readText() })
                characters=List(a.length()) { a.getString(it) }; check(characters.size==18709)
                if(dev.folio.scanner.BuildConfig.DEBUG || isolatedHardwareProbe) { profilePending+=requireNotNull(det); profilePending+=requireNotNull(rec) }
            } catch(e:Throwable) { det?.close(); rec?.close(); det=null; rec=null; options.close(); retainedOptions=null; throw e }
        }
        try { sessions(hardware) } catch(error:OrtException) {
            if(!hardware) throw error
            hardware=false; fallback=true; fallbackReason="$requestedProvider initialization failed: ${error.code}"; sessions(false)
        }
        initializationMs=now()-start
    }
    private fun run(session:OrtSession,data:FloatArray,shape:LongArray):Pair<FloatArray,LongArray> =
        OnnxTensor.createTensor(env,FloatBuffer.wrap(data),shape).use { tensor ->
            session.run(mapOf("x" to tensor)).use { output -> val t=output[0] as OnnxTensor; val info=t.info as TensorInfo
                val buffer=t.floatBuffer; val values=FloatArray(buffer.remaining()); buffer.get(values); values to info.shape }
        }
    @Synchronized fun recognize(bitmap:Bitmap,checkCancelled:()->Unit={}):OcrMeasurement = dev.folio.scanner.pdfanalysis.HeavyInferenceGate.run(context,checkCancelled) { recognizeProtected(bitmap,checkCancelled) }
    private fun recognizeProtected(bitmap:Bitmap,checkCancelled:()->Unit):OcrMeasurement {
        try { return recognizeOnce(bitmap,checkCancelled) } catch(error:OrtException) {
            if(!hardware) throw error
            close(); fallback=true; hardware=false; fallbackReason="$requestedProvider inference failed: ${error.code}"; return recognizeOnce(bitmap,checkCancelled)
        }
    }
    private fun providerFor(session:OrtSession)=stageProviders[session]?.joinToString("+")
        ?: if(hardware) "$requestedProvider requested; assignment unverified" else "CPUExecutionProvider"
    private fun recognizeOnce(bitmap:Bitmap,checkCancelled:()->Unit):OcrMeasurement {
        val start=now(); val cold=det==null || rec==null; checkCancelled(); load(); checkCancelled()
        val source=BitmapUtils.bitmapToBGRMat(bitmap)
        try {
            var mark=now(); val input=DetPreprocessor.preprocess(source,1536,"max",1536,"BGR"); val prep=now()-mark
            mark=now(); val (probability,shape)=run(requireNotNull(det),input.tensorData,input.shape); val detection=now()-mark; runCatching { endProfile(requireNotNull(det)) }; checkCancelled()
            require(shape.size==4 && shape[0]==1L && shape[1]==1L)
            mark=now(); val boxes=BoxSorter.sortInReadingOrder(DBPostProcessor.process(probability,shape,.2f,.45f,1.4f,3000,false,"fast","quad",source.rows(),source.cols())); var post=now()-mark
            var recognition=0L; var recPrep=0L; val regions=mutableListOf<TextRegion>()
            for(box in boxes) {
                checkCancelled(); val crop=QuadTextCrop.crop(source,box)
                try {
                    mark=now(); val r=RecPreprocessor.preprocessBatch(listOf(crop)); recPrep+=now()-mark
                    mark=now(); val (scores,s)=run(requireNotNull(rec),r.tensorData,r.shape); recognition+=now()-mark; runCatching { endProfile(requireNotNull(rec)) }
                    require(s.size==3 && s[0]==1L && s[2]==18710L)
                    mark=now(); val decoded=CTCDecoder.decode(scores,s,characters).single(); post+=now()-mark
                    regions+=TextRegion(decoded.first,box.points.flatMap { listOf((it.x/source.cols()).toDouble().coerceIn(0.0,1.0),(it.y/source.rows()).toDouble().coerceIn(0.0,1.0)) },decoded.second.toDouble().coerceIn(0.0,1.0))
                } finally { crop.release() }
            }
            return OcrMeasurement(regions,if(cold) initializationMs else 0,prep+recPrep,detection,recognition,post,now()-start,input.shape[3].toInt(),input.shape[2].toInt(),
                if(measuredProviders.isNotEmpty()) measuredProviders.joinToString("+") else if(hardware) "$requestedProvider requested; assignment unverified" else "CPUExecutionProvider",
                fallback || hardware && "CPUExecutionProvider" in measuredProviders,
                providerFor(requireNotNull(det)),if(boxes.isEmpty()) "not run (no regions)" else providerFor(requireNotNull(rec)),
                if(dev.folio.scanner.BuildConfig.DEBUG) OrtEnvironment.getAvailableProviders().joinToString() else "",
                fallbackReason.ifEmpty { if(hardware && measuredProviders==setOf("CPUExecutionProvider")) "$requestedProvider requested; all profiled operators assigned to ORT CPU" else "" })
        } finally { source.release() }
    }
}

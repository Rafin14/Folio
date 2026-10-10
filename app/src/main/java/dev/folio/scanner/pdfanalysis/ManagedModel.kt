package dev.folio.scanner.pdfanalysis

import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.util.JsonReader
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.UUID

data class TensorInput(val values:FloatArray,val shape:LongArray)
data class TensorOutput(val values:FloatArray,val shape:LongArray)
class ManagedModel(private val context:Context,private val asset:String,private val mode:String,private val skipGpu:Boolean=false,private val progress:(String)->Unit={}):AutoCloseable {
 private val env=OrtEnvironment.getEnvironment()
 private var session:OrtSession?=null;private var options:OrtSession.SessionOptions?=null
 private var profilePending=false;private var candidateIndex=0;private var requested=""
 val attempts=mutableListOf<String>();var actual="Not executed";private set
 var loadMs=0L;private set
 val available=OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
 private val choices=when(mode) { "CPU" -> listOf("CPU");"XNNPACK" -> listOf("XNNPACK","CPU");"GPU" -> listOf("WEBGPU","CPU"); else -> listOf("WEBGPU","NNAPI","CPU") }
 private fun notifyStage(message:String) {if(dev.folio.scanner.BuildConfig.DEBUG) {val trace=File(context.noBackupFilesDir,"provider-trace.txt");if(trace.length()>65536) trace.writeText("");trace.appendText(message+"\n")};progress(message)}
 private fun now()=android.os.SystemClock.elapsedRealtime()
 private fun modelFile():File {
  val out=File(context.noBackupFilesDir,asset).apply { parentFile!!.mkdirs() }
  if(!out.exists() || dev.folio.scanner.backup.hashFile(out)!="d24809294b2f9f1a9a2767043a64df2714b66e5be056887be2233d1117d784f6") {val tmp=File(out.path+".writing");context.assets.open(asset).use { input -> tmp.outputStream().use { input.copyTo(it,65536) } };check(dev.folio.scanner.backup.hashFile(tmp)=="d24809294b2f9f1a9a2767043a64df2714b66e5be056887be2233d1117d784f6");check(tmp.renameTo(out))}
  return out
 }
 private fun openNext() {
  while(candidateIndex<choices.size) {
   requested=choices[candidateIndex++]
   if(requested!="CPU" && requested !in available || requested=="NNAPI" && Build.VERSION.SDK_INT<29 || requested=="WEBGPU" && skipGpu) { attempts+="$requested: unavailable or disabled after native failure";continue }
   notifyStage("$asset: $requested initialization (unverified)")
   val opts=OrtSession.SessionOptions();options=opts;val start=now()
   try {
    opts.setIntraOpNumThreads(2);opts.setInterOpNumThreads(1)
    opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    when(requested) {
     "NNAPI" -> opts.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
     "WEBGPU" -> {opts.setMemoryPatternOptimization(false);opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);opts.addWebGPU(emptyMap())}
     "XNNPACK" -> opts.addXnnpack(mapOf("intra_op_num_threads" to "2"))
    }
    opts.enableProfiling(File(context.cacheDir,"provider-${UUID.randomUUID()}").path)
    session=env.createSession(modelFile().path,opts);profilePending=true;loadMs+=now()-start
    attempts+="$requested: initialized (execution not yet verified)";return
   } catch(e:OrtException) {loadMs+=now()-start;attempts+="$requested: initialization failed ${e.code}";disposeSession()}
  }
  error("No compatible provider could load $asset. ${attempts.joinToString()}")
 }
 private fun measuredProviders():Set<String> {
  if(!profilePending) return emptySet();profilePending=false
  val file=File(requireNotNull(session).endProfiling());val result=linkedSetOf<String>()
  try {JsonReader(file.reader()).use { reader ->
   reader.beginArray();while(reader.hasNext()) {reader.beginObject();while(reader.hasNext()) {
    if(reader.nextName()=="args") { reader.beginObject();while(reader.hasNext()) {if(reader.nextName()=="provider") result+=reader.nextString() else reader.skipValue()};reader.endObject() } else reader.skipValue()
   };reader.endObject()};reader.endArray()
  }} finally {file.delete()};return result
 }
 @Synchronized fun run(inputs:Map<String,TensorInput>,outputName:String?=null):TensorOutput = HeavyInferenceGate.run(context) { execute(inputs,outputName) }
 private fun execute(inputs:Map<String,TensorInput>,outputName:String?):TensorOutput {
  while(true) {
   if(session==null) openNext();val s=requireNotNull(session)
   require(inputs.keys==s.inputNames) {"Unexpected inputs: ${s.inputNames}"}
   val tensors=inputs.mapValues { (_,v) -> OnnxTensor.createTensor(env,FloatBuffer.wrap(v.values),v.shape) }
   try {
    val name=outputName ?: s.outputNames.first()
    if(profilePending) notifyStage("$asset: $requested inference (unverified)")
    val output=s.run(tensors,setOf(name)).use { result ->
     val t=result.get(name).get() as OnnxTensor;val info=t.info as TensorInfo
     require(info.type==OnnxJavaType.FLOAT);val b=t.floatBuffer;val v=FloatArray(b.remaining());b.get(v);TensorOutput(v,info.shape)
    }
    if(profilePending) {
     val observed=measuredProviders();actual=observed.ifEmpty {setOf("Unverified: profile contains no assigned provider")}.joinToString(" + ")
     val expected=when(requested){"NNAPI"->"NnapiExecutionProvider";"WEBGPU"->"WebGpuExecutionProvider";"XNNPACK"->"XnnpackExecutionProvider";else->"CPUExecutionProvider"}
     if(requested!="CPU" && observed.none { it.equals(expected,true) }) {attempts+="$requested: no executed operators assigned to requested provider; observed $actual";disposeSession();continue}
     attempts+="$requested: ACTIVE, profiled $actual"
    }
    return output
   } catch(e:OrtException) {attempts+="$requested: inference failed ${e.code}";disposeSession();if(requested=="CPU") throw e}
   finally {tensors.values.forEach { it.close() }}
  }
 }
 fun diagnostics()="$asset\nAvailable: ${available.joinToString()}\nActual: $actual\nStatus: ${if(actual.startsWith("Unverified")) "UNVERIFIED" else if(requested=="CPU" && mode!="CPU") "FALLBACK" else if(actual.contains("CPUExecutionProvider") && requested!="CPU") "ACTIVE with CPU fallback" else if(actual=="Not executed") "WAITING" else "ACTIVE"}\nLoad: $loadMs ms\n${attempts.joinToString("\n")}"
 private fun disposeSession() {session?.close();session=null;options?.close();options=null;profilePending=false}
 @Synchronized override fun close()=disposeSession()
}

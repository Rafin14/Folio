package dev.folio.scanner.pdfanalysis

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

data class LayoutResult(val regions:List<LayoutDetection>,val preprocessMs:Long,val inferenceMs:Long,val postprocessMs:Long,val totalMs:Long)
class PPDocLayoutV3Detector(context:Context,mode:String,skipGpu:Boolean=false,progress:(String)->Unit={}):AutoCloseable {
 val model=ManagedModel(context,"models/layout/PP-DocLayoutV3.onnx",mode,skipGpu,progress)
 private fun now()=android.os.SystemClock.elapsedRealtime()
 fun detect(bitmap:Bitmap):LayoutResult {
  check(OpenCVLoader.initLocal());val start=now();val rgba=Mat();val rgb=Mat();val resized=Mat()
  val input:FloatArray
  try {
   Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB)
   Imgproc.resize(rgb,resized,Size(800.0,800.0),0.0,0.0,Imgproc.INTER_LINEAR)
   val bytes=ByteArray(800*800*3);resized.get(0,0,bytes);input=FloatArray(bytes.size)
   for(i in 0 until 800*800) for(c in 0..2) input[c*800*800+i]=normalizedChannel(bytes[i*3+c].toInt() and 255,c)
  } finally {rgba.release();rgb.release();resized.release()}
  val prep=now()-start;var mark=now()
  val loadBefore=model.loadMs
  val out=model.run(mapOf("image" to TensorInput(input,longArrayOf(1,3,800,800)),"im_shape" to TensorInput(floatArrayOf(800f,800f),longArrayOf(1,2)),"scale_factor" to TensorInput(floatArrayOf(800f/bitmap.height,800f/bitmap.width),longArrayOf(1,2))),"fetch_name_0")
  val inference=(now()-mark-(model.loadMs-loadBefore)).coerceAtLeast(0);require(out.shape.size==2 && out.shape[1]==7L) {"Invalid layout output shape ${out.shape.contentToString()}"}
  mark=now();val result=decodeLayout(out.values,bitmap.width,bitmap.height);val post=now()-mark
  return LayoutResult(result,prep,inference,post,now()-start)
 }
 override fun close()=model.close()
}

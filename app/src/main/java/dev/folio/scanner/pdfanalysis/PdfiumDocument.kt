package dev.folio.scanner.pdfanalysis

import android.graphics.Bitmap
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

internal object PdfiumNative {
    init { System.loadLibrary("pdfium"); System.loadLibrary("folio_pdf") }
    external fun open(path:String):Long
    external fun close(document:Long)
    external fun count(document:Long):Int
    external fun size(document:Long,page:Int):DoubleArray
    external fun render(document:Long,page:Int,width:Int,height:Int):IntArray
    external fun text(document:Long,page:Int):String
    external fun inspect(document:Long,page:Int,width:Int,height:Int):String
    external fun originalJpeg(document:Long,page:Int,objectIndex:Int,target:String)
    external fun vectorRegion(document:Long,page:Int,width:Int,height:Int,left:Float,top:Float,right:Float,bottom:Float,target:String)
}

/** All PDFium calls are serialized in JNI. A document owns only one page render at a time. */
class PdfiumDocument(file:File):AutoCloseable {
    private var handle=PdfiumNative.open(file.path)
    val count get()=PdfiumNative.count(active()).also { require(it in 1..500) { "Choose a PDF containing 1 to 500 pages." } }
    private fun active()=handle.also { check(it!=0L) { "PDF is closed." } }
    fun size(page:Int)=PdfiumNative.size(active(),page)
    fun text(page:Int)=PdfiumNative.text(active(),page)
    fun render(page:Int,edge:Int=1800):Bitmap {
        val size=size(page); require(size.all { it.isFinite() && it>0 })
        val scale=edge/size.max(); val w=(size[0]*scale).roundToInt().coerceAtLeast(1);val h=(size[1]*scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(PdfiumNative.render(active(),page,w,h),w,h,Bitmap.Config.ARGB_8888)
    }
    fun inspect(page:Int,width:Int,height:Int)=JSONObject(PdfiumNative.inspect(active(),page,width,height))
    fun originalJpeg(page:Int,objectIndex:Int,target:File)=PdfiumNative.originalJpeg(active(),page,objectIndex,target.path)
    fun vectorRegion(page:AnalysisPage,box:Box,target:File)=PdfiumNative.vectorRegion(active(),page.index,page.width,page.height,box.left,box.top,box.right,box.bottom,target.path)
    override fun close() { if(handle!=0L) {PdfiumNative.close(handle);handle=0} }
}

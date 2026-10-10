package dev.folio.scanner.pdfanalysis

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import dev.folio.scanner.ocr.OcrEngine
import dev.folio.scanner.ocr.OCR_MODEL
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor

/** Native/GPU work stays in a private process. One retained layout and OCR session per job. */
class PdfAnalysisService:Service() {
    private val executor=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean();private val busy=AtomicBoolean()
    private var layout:PPDocLayoutV3Detector?=null;private var ocr:OcrEngine?=null;private var policy=""
    private val messenger=Messenger(Handler(Looper.getMainLooper()) { message ->
        when(message.what) {
            2 -> {cancelled.set(true);val reply=message.replyTo;executor.execute {if(reply!=null) report(reply,7,"Stopped")}}
            1 -> {
                val reply=message.replyTo
                if(!busy.compareAndSet(false,true)) report(reply,5,"Previous analysis is still finishing. Retry shortly.")
                else {cancelled.set(false);report(reply,6,android.os.Process.myPid().toString());val data=message.data;executor.execute { process(data,reply) }}
            }
        };true
    })
    override fun onBind(intent:Intent)=messenger.binder
    private fun report(reply:Messenger,kind:Int,value:String) { runCatching { reply.send(Message.obtain(null,kind).apply { data=Bundle().apply { putString("text",value) } }) } }
    private fun check() { if(cancelled.get()) throw java.util.concurrent.CancellationException("Analysis cancelled") }
    private fun process(data:Bundle,reply:Messenger) {
        var completed:Int?=null
        try {
            val folder=File(requireNotNull(data.getString("folder"))).canonicalFile
            require(folder.toPath().startsWith(File(filesDir,"pdf-utility").canonicalFile.toPath()) && folder.isDirectory)
            val index=data.getInt("page");val cpu=data.getBoolean("cpu");val nextPolicy=if(cpu) "CPU" else "AUTO"
            if(policy!=nextPolicy) {layout?.close();ocr?.close();layout=null;ocr=null;policy=nextPolicy}
            PdfiumDocument(File(folder,"input-0")).use { pdf ->
                require(index in 0 until pdf.count);check();report(reply,3,"Reading page ${index+1}")
                val bitmap=pdf.render(index)
                try {
                    val size=pdf.size(index);val inspection=pdf.inspect(index,bitmap.width,bitmap.height)
                    val mode=OcrMode.valueOf(data.getString("mode") ?: OcrMode.LAYOUT_AWARE.name)
                    val array=inspection.getJSONArray("characters")
                    val lines=JSONArray();val diagnostics=StringBuilder()
                    var regions:MutableList<AnalysisRegion>
                    if(mode==OcrMode.FULL_PAGE) {
                        check();report(reply,3,"Full Page OCR on page ${index+1}")
                        val reader=ocr ?: OcrEngine(this).apply {forceCpuForBenchmark=cpu;isolatedHardwareProbe=!cpu}.also {ocr=it}
                        val read=reader.recognize(bitmap,::check)
                        regions=read.regions.mapIndexed {n,line ->
                            val xs=line.points.filterIndexed {i,_->i%2==0}.map {it.toFloat()*bitmap.width}
                            val ys=line.points.filterIndexed {i,_->i%2==1}.map {it.toFloat()*bitmap.height}
                            val box=Box(xs.min(),ys.min(),xs.max(),ys.max())
                            lines.put(org.json.JSONObject().put("text",line.text).put("box",box.json()))
                            AnalysisRegion(n,"text",box,n,line.text)
                        }.toMutableList()
                        diagnostics.append("Full Page OCR · $OCR_MODEL · ${read.totalMs} ms\nprovider=${read.provider}; CPU fallback=${read.cpuFallback}; ${read.fallbackReason}\nLayout detection bypassed\n")
                    } else {
                    check();report(reply,3,"Finding document layout on page ${index+1}")
                    val detector=layout ?: PPDocLayoutV3Detector(this,nextPolicy,cpu) {check();report(reply,3,it)}.also {layout=it}
                    val found=detector.detect(bitmap);check()
                    val chars=List(array.length()) {array.getJSONObject(it)};val used=mutableSetOf<Int>()
                    diagnostics.append("PP-DocLayoutV3 IR10 · ${found.inferenceMs} ms\n${detector.model.diagnostics()}\nOCR $OCR_MODEL\n")
                    regions=found.regions.map { region ->
                        check();var value=nativeRegion(chars,region,used)
                        if(value.text.isBlank() && region.label in TEXT_LABELS) {
                            report(reply,3,"Reading ${region.label} on page ${index+1}")
                            val b=region.box.clipped(bitmap.width,bitmap.height)
                            val x=floor(b.left).toInt();val y=floor(b.top).toInt();val w=(ceil(b.right).toInt()-x).coerceAtLeast(1);val h=(ceil(b.bottom).toInt()-y).coerceAtLeast(1)
                            val crop=Bitmap.createBitmap(bitmap,x,y,w.coerceAtMost(bitmap.width-x),h.coerceAtMost(bitmap.height-y))
                            try {
                                val reader=ocr ?: OcrEngine(this).apply {forceCpuForBenchmark=cpu;isolatedHardwareProbe=!cpu}.also {ocr=it}
                                val read=reader.recognize(crop,::check)
                                value=value.copy(text=read.regions.joinToString("\n") {it.text})
                                read.regions.forEach {line ->val xs=line.points.filterIndexed {i,_->i%2==0}.map {x+it.toFloat()*crop.width};val ys=line.points.filterIndexed {i,_->i%2==1}.map {y+it.toFloat()*crop.height}
                                    lines.put(org.json.JSONObject().put("text",line.text).put("box",Box(xs.min(),ys.min(),xs.max(),ys.max()).json()))
                                }
                                diagnostics.append("${region.label}: ${read.totalMs} ms; provider=${read.provider}; CPU fallback=${read.cpuFallback}; ${read.fallbackReason}\n")
                            } finally {if(crop!==bitmap) crop.recycle()}
                        };value
                    }.toMutableList()
                    val remaining=chars.indices.filter {it !in used}
                    val remainder=buildString { remaining.forEach { i -> val c=chars[i].getInt("unicode");if(Character.isValidCodePoint(c)) append(Character.toChars(c)) } }.trim()
                    if(remainder.count(Char::isLetterOrDigit)>1) regions+=AnalysisRegion(-1,"text",Box(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()),(regions.maxOfOrNull {it.order} ?: 0)+1,remainder,native=true)
                    if(regions.none {it.text.isNotBlank()} && found.regions.none {it.label in TEXT_LABELS}) {
                        // Honest fallback when layout found no text regions: run the existing OCR on this page.
                        val reader=ocr ?: OcrEngine(this).apply {forceCpuForBenchmark=cpu;isolatedHardwareProbe=!cpu}.also {ocr=it}
                        val read=reader.recognize(bitmap,::check)
                        read.regions.forEachIndexed { n,r ->val xs=r.points.filterIndexed {i,_->i%2==0}.map {it.toFloat()*bitmap.width};val ys=r.points.filterIndexed {i,_->i%2==1}.map {it.toFloat()*bitmap.height}
                            regions+=AnalysisRegion(10000+n,"text",Box(xs.min(),ys.min(),xs.max(),ys.max()),10000+n,r.text)
                        }
                        diagnostics.append("Page OCR fallback: ${read.provider}; ${read.totalMs} ms\n")
                    }
                    }
                    val image=File(folder,"analysis-$index.jpg")
                    image.outputStream().use {check(bitmap.compress(Bitmap.CompressFormat.JPEG,94,it))}
                    check()
                    val result=AnalysisPage(index,bitmap.width,bitmap.height,size[0],size[1],regions,inspection.getJSONArray("objects"),diagnostics.append("PSS: ${Debug.getPss()} KB").toString(),array,lines,inspection.optInt("annotations"),mode,data.getString("request").orEmpty())
                    dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(File(folder,if(mode==OcrMode.FULL_PAGE) "full-ocr-$index.json" else "analysis-$index.json"),result.json().toString().toByteArray())
                    if(mode==OcrMode.LAYOUT_AWARE && data.getBoolean("replaceOcr")) File(folder,"full-ocr-$index.json").delete()
                    completed=index
                } finally {bitmap.recycle()}
            }
        } catch(_:OutOfMemoryError) {report(reply,5,"Insufficient memory for this page. Close other apps and retry using CPU.")}
        catch(_:java.util.concurrent.CancellationException) {report(reply,5,"Analysis cancelled")}
        catch(error:Exception) {report(reply,5,error.message?.take(240) ?: "PDF analysis failed")}
        finally {busy.set(false);completed?.let {report(reply,4,it.toString())}}
    }
    override fun onDestroy() {cancelled.set(true);executor.execute {layout?.close();ocr?.close()};executor.shutdown();super.onDestroy()}
}

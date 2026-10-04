package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.ocr.OcrEngine
import org.junit.Assert.*
import org.junit.Test

class OcrProviderTest {
    @Test fun automaticHardwareRequestAndCpuBaselineProduceTheSameEnglishText() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=Bitmap.createBitmap(800,1000,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=40f }
        canvas.drawText("Invoice paid",50f,150f,ink); canvas.drawText("Invoice number 12345",50f,250f,ink)
        try {
            val cpu=OcrEngine(context).use { engine -> engine.forceCpuForBenchmark=true; engine.recognize(bitmap) }
            val auto=OcrEngine(context).use { engine -> engine.probeWebGpuForBenchmark=InstrumentationRegistry.getArguments().getString("probeWebGPU")=="true"; engine.recognize(bitmap) }
            assertEquals(cpu.regions.map { it.text },auto.regions.map { it.text })
            assertTrue(cpu.regions.any { it.text.contains("Invoice",true) })
            assertEquals("CPUExecutionProvider",cpu.provider)
            assertTrue(auto.provider.contains("CPUExecutionProvider") || auto.provider.contains("NnapiExecutionProvider") || auto.provider.contains("WebGpuExecutionProvider"))
            val details="device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} available=${auto.availableProviders} CPU detProvider=${cpu.detectionProvider} recProvider=${cpu.recognitionProvider} init=${cpu.initializationMs} det=${cpu.detectionMs} rec=${cpu.recognitionMs} total=${cpu.totalMs}; AUTO detProvider=${auto.detectionProvider} recProvider=${auto.recognitionProvider} init=${auto.initializationMs} det=${auto.detectionMs} rec=${auto.recognitionMs} total=${auto.totalMs} fallback=${auto.cpuFallback} reason=${auto.fallbackReason}"
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("ocrProviders",details) })
            assertEquals("CPUExecutionProvider",cpu.detectionProvider)
            assertEquals("CPUExecutionProvider",cpu.recognitionProvider)
            android.util.Log.d("FolioOCRBenchmark","model=PP-OCRv6_small CPU provider=${cpu.provider} init=${cpu.initializationMs} inference=${cpu.detectionMs+cpu.recognitionMs} total=${cpu.totalMs}; AUTO provider=${auto.provider} init=${auto.initializationMs} inference=${auto.detectionMs+auto.recognitionMs} total=${auto.totalMs} cpuFallback=${auto.cpuFallback}")
        } finally { bitmap.recycle() }
    }
}

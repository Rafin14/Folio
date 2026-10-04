package dev.folio.scanner

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.folio.scanner.data.PageLayout
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PrintingTest {
    @Test fun androidHttpsTransportAcceptsConditionalPatch() {
        val connection=java.net.URL("https://www.googleapis.com/drive/v3/files").openConnection() as java.net.HttpURLConnection
        try { connection.requestMethod="PATCH"; connection.setRequestProperty("If-Match","test-version"); assertEquals("PATCH",connection.requestMethod) }
        finally { connection.disconnect() }
    }
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun write(source:File,file:File,attributes:PrintAttributes,ranges:Array<PageRange>,cancel:Boolean=false):Int = runBlocking {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE).use { descriptor ->
            try { writePrintedPages(source,"Print fixture",attributes,ranges,descriptor,CancellationSignal().apply { if(cancel) cancel() }).sumOf { it.end-it.start+1 } }
            catch(error:android.os.OperationCanceledException) { if(!cancel) throw error; 0 }
        }
    }
    @Test fun nativeAdapterAllSelectedOrderedAspectPreservingAndCancellable()=runBlocking {
        val folder=File(inst.targetContext.cacheDir,"printing-${UUID.randomUUID()}").apply { mkdirs() }
        val source=File(folder,"source.pdf")
        val images=listOf(Color.RED,Color.GREEN,Color.BLUE).mapIndexed { index,color ->
            File(folder,"$index.jpg").also { target -> val bitmap=Bitmap.createBitmap(400,800,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { bitmap.recycle() } }
        }
        val engine=PdfEngine(); engine.generate(images,source,"Print fixture",layouts=List(3) { PageLayout("A5") })
        val adapter=FolioPrintAdapter(source,"Print fixture")
        try {
            val attributes=printAttributes(source); assertEquals(3,engine.count(source))
            val all=File(folder,"all.pdf"); assertEquals(3,write(source,all,attributes,arrayOf(PageRange.ALL_PAGES)))
            val selected=File(folder,"selected.pdf"); assertEquals(1,write(source,selected,attributes,arrayOf(PageRange(1,1))))
            for(file in listOf(all,selected)) PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(if(file==all) 3 else 1,renderer.pageCount)
                repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                    assertEquals(419.0,page.width.toDouble(),1.0); assertEquals(595.0,page.height.toDouble(),1.0)
                    val bitmap=Bitmap.createBitmap(420,596,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                    try {
                        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val color=bitmap.getPixel(210,298); val expected=if(file==all) index else 1
                        assertTrue(when(expected) { 0 -> Color.red(color)>200 && Color.green(color)<50; 1 -> Color.green(color)>200 && Color.red(color)<50; else -> Color.blue(color)>200 && Color.red(color)<50 })
                        // The 1:2 content is fitted, not stretched to the A5 aspect ratio.
                        assertTrue(Color.red(bitmap.getPixel(5,298))>200 && Color.green(bitmap.getPixel(5,298))>200)
                    } finally { bitmap.recycle() }
                } }
            }
            assertEquals(0,write(source,File(folder,"cancel.pdf"),attributes,arrayOf(PageRange.ALL_PAGES),true))
        } finally { inst.runOnMainSync { adapter.onFinish() }; folder.deleteRecursively() }
    }
    @Test fun paperPresetsAndCustomOriginalProduceCorrectPrintAttributeHints()=runBlocking {
        val folder=File(inst.targetContext.cacheDir,"print-sizes-${UUID.randomUUID()}").apply { mkdirs() }
        val image=File(folder,"page.jpg"); val bitmap=Bitmap.createBitmap(300,600,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try { image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) } } finally { bitmap.recycle() }
        try {
            for(size in PageLayout.sizes) {
                val paper=PageLayout(size,widthMm=180.5,heightMm=260.25); val file=File(folder,"$size.pdf")
                PdfEngine().generate(listOf(image),file,"Print sizes",layouts=listOf(paper))
                val media=printAttributes(file).mediaSize!!
                val expected=paper.dimensions ?: (50.8 to 101.6)
                assertEquals(expected.first/25.4*1000,media.widthMils.toDouble(),10.0)
                assertEquals(expected.second/25.4*1000,media.heightMils.toDouble(),10.0)
                val id=when(size) { "A4" -> PrintAttributes.MediaSize.ISO_A4.id; "A5" -> PrintAttributes.MediaSize.ISO_A5.id; "Letter" -> PrintAttributes.MediaSize.NA_LETTER.id; "Legal" -> PrintAttributes.MediaSize.NA_LEGAL.id; else -> "folio-page" }
                assertEquals(id,media.id)
            }
        } finally { folder.deleteRecursively() }
    }
}

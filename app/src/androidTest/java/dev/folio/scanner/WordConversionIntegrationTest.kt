package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class WordConversionIntegrationTest {
    @Test fun nativeAndScannedTablesConvertToEditableDocxWithRealAnalysis()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val folder=File(context.filesDir,"pdf-utility/${UUID.randomUUID()}").apply {mkdirs()}
        val image=Bitmap.createBitmap(1400,1800,Bitmap.Config.ARGB_8888)
        try {
            Canvas(image).apply {
                drawColor(Color.WHITE);val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=64f}
                drawText("SCANNED INVOICE",100f,200f,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=5f;drawRect(100f,500f,1300f,1300f,paint);drawLine(700f,500f,700f,1300f,paint);drawLine(100f,900f,1300f,900f,paint)
                paint.style=Paint.Style.FILL;drawText("Name",150f,700f,paint);drawText("Total",800f,700f,paint);drawText("Offline",150f,1100f,paint);drawText("42",850f,1100f,paint)
            }
            val scan=ByteArrayOutputStream().apply {image.compress(Bitmap.CompressFormat.JPEG,98,this)}.toByteArray()
            val photo=Bitmap.createBitmap(300,150,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val jpeg=try {ByteArrayOutputStream().apply {photo.compress(Bitmap.CompressFormat.JPEG,98,this)}.toByteArray()} finally {photo.recycle()}
            PdfDocument(PdfWriter(File(folder,"input-0"))).use {pdf ->
                val canvas=PdfCanvas(pdf.addNewPage());val font=PdfFontFactory.createFont()
                fun text(x:Double,y:Double,s:String,size:Float=12f) {canvas.beginText().setFontAndSize(font,size).moveText(x,y).showText(s).endText()}
                text(50.0,760.0,"Folio Editable Report",24f);text(50.0,700.0,"Left column text");text(320.0,700.0,"Right column text")
                canvas.rectangle(50.0,400.0,400.0,180.0).stroke();canvas.moveTo(50.0,490.0).lineTo(450.0,490.0).stroke();canvas.moveTo(250.0,400.0).lineTo(250.0,490.0).stroke()
                text(80.0,525.0,"Invoice totals");text(80.0,440.0,"Item A");text(300.0,440.0,"20 USD")
                canvas.addImageFittedIntoRectangle(ImageDataFactory.create(jpeg),Rectangle(50f,80f,100f,50f),false)
                PdfCanvas(pdf.addNewPage()).addImageFittedIntoRectangle(ImageDataFactory.create(scan),Rectangle(0f,0f,595f,842f),false)
            }
            val source=File(folder,"input-0").readBytes()
            val connection=AnalysisConnection(context) {};try {connection.start();connection.page(folder,0,true);connection.page(folder,1,true)} finally {connection.closeSafely()}
            val analyzed=List(2) {AnalysisPage.parse(JSONObject(File(folder,"analysis-$it.json").readText()))}
            assertTrue(analyzed[0].text.contains("Editable Report"));assertTrue(analyzed[1].text.contains("INVOICE",true))
            assertTrue("Real layout must identify the ruled table",analyzed.all {p ->p.regions.any {it.label=="table"}})
            WordMode.entries.forEach {mode ->
                val output=File(context.getExternalFilesDir(null),"major-update-word-${mode.name}.docx")
                val result=PdfiumDocument(File(folder,"input-0")).use {pdf ->writeDocx(output,folder,2,mode,{i ->val bitmap=BitmapFactory.decodeFile(File(folder,"analysis-$i.jpg").path);try {reconstructWordPage(analyzed[i],bitmap,pdf,folder)} finally {bitmap.recycle()}})}
                assertEquals(2,result.tables);assertTrue(result.paragraphs>=2);assertTrue(result.figures>=1)
                ZipFile(output).use {zip ->val xml=zip.getInputStream(zip.getEntry("word/document.xml")).bufferedReader().use {it.readText()}
                    assertTrue(xml.contains("Invoice totals"));assertTrue(xml.contains("Offline"));assertTrue(xml.contains("gridSpan"));assertTrue(xml.contains("Name"));assertTrue(xml.contains("42"))
                    assertEquals(mode==WordMode.LAYOUT,xml.contains("framePr"));assertTrue(zip.entries().asSequence().any {it.name.startsWith("word/media/")})
                }
            }
            assertArrayEquals(source,File(folder,"input-0").readBytes())
        } finally {image.recycle();folder.deleteRecursively()}
    }
}

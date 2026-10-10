package dev.folio.scanner

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import dev.folio.scanner.pdfanalysis.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class FigureExtractionTest {
    @Test fun originalJpegVectorFigureAndAdjustedRasterExportsPreserveSource() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val folder=File(context.cacheDir,"figure-test-${UUID.randomUUID()}").apply {mkdirs()}
        val input=File(folder,"source.pdf");val bitmap=Bitmap.createBitmap(600,300,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
        try {
            val jpeg=ByteArrayOutputStream().apply {bitmap.compress(Bitmap.CompressFormat.JPEG,95,this)}.toByteArray()
            PdfDocument(PdfWriter(input)).use {pdf ->
                PdfCanvas(pdf.addNewPage()).addImageFittedIntoRectangle(ImageDataFactory.create(jpeg),Rectangle(50f,500f,200f,100f),false)
                val page=pdf.addNewPage().setRotation(90);val canvas=PdfCanvas(page)
                canvas.rectangle(50.0,500.0,200.0,100.0).stroke()
                canvas.beginText().setFontAndSize(PdfFontFactory.createFont(),12f).moveText(70.0,550.0).showText("Figure label").endText()
                canvas.beginText().setFontAndSize(PdfFontFactory.createFont(),12f).moveText(50.0,740.0).showText("Unrelated outside text").endText()
                val masked=com.itextpdf.kernel.pdf.xobject.PdfImageXObject(ImageDataFactory.create(jpeg))
                val mask=PdfStream(ByteArray(600*300) {128.toByte()}).apply {put(PdfName.Type,PdfName.XObject);put(PdfName.Subtype,PdfName.Image);put(PdfName.Width,PdfNumber(600));put(PdfName.Height,PdfNumber(300));put(PdfName.BitsPerComponent,PdfNumber(8));put(PdfName.ColorSpace,PdfName.DeviceGray)}
                masked.pdfObject.put(PdfName.SMask,mask)
                PdfCanvas(pdf.addNewPage()).addXObjectFittedIntoRectangle(masked,Rectangle(50f,500f,200f,100f))
            }
            val original=input.readBytes()
            PdfiumDocument(input).use {pdf ->
                fun page(index:Int):AnalysisPage {val image=pdf.render(index,842);return try {val size=pdf.size(index);val inspection=pdf.inspect(index,image.width,image.height);AnalysisPage(index,image.width,image.height,size[0],size[1],emptyList(),inspection.getJSONArray("objects"),"",annotations=inspection.optInt("annotations"))} finally {image.recycle()}}
                val p0=page(0);val figures=reconcileFigures(p0);assertEquals(1,figures.size);assertEquals(FigureFormat.JPEG,figures.single().format)
                val direct=extractFigure(pdf,p0,figures.single(),folder);assertArrayEquals(jpeg,direct.file.readBytes())
                val adjusted=figures.single().copy(box=figures.single().box.copy(right=figures.single().box.right-20))
                val rendered=extractFigure(pdf,p0,adjusted,folder);assertEquals(FigureFormat.PNG,rendered.format)
                val cropped=BitmapFactory.decodeFile(rendered.file.path);try {assertTrue(cropped.width>500);assertTrue(Color.red(cropped.getPixel(cropped.width/2,cropped.height/2))>240)} finally {cropped.recycle()}
                val p1=page(1);val path=List(p1.objects.length()) {p1.objects.getJSONObject(it)}.first {it.getInt("type")==2};val b=boxFrom(path.getJSONArray("box"));val region=Box(b.left-6,b.top-6,b.right+6,b.bottom+6)
                val figure=Figure("vector",1,region,"chart",FigureFormat.VECTOR)
                assertEquals(FigureFormat.VECTOR,figureFormat(p1,region).first)
                val vector=extractFigure(pdf,p1,figure,folder);assertEquals(FigureFormat.VECTOR,vector.format)
                PdfiumDocument(vector.file).use {out ->assertTrue(out.text(0).contains("Figure label"));assertFalse(out.text(0).contains("Unrelated"));assertTrue(out.size(0).all {it>10 && it<300});out.render(0,800).recycle()}
                val maskedPage=page(2);val maskedFigure=reconcileFigures(maskedPage).single()
                assertEquals("A soft mask must be preserved through rendering",FigureFormat.PNG,maskedFigure.format)
                assertEquals(FigureFormat.PNG,extractFigure(pdf,maskedPage,maskedFigure,folder).format)
            }
            assertArrayEquals(original,input.readBytes())
        } finally {bitmap.recycle();folder.deleteRecursively()}
    }
}

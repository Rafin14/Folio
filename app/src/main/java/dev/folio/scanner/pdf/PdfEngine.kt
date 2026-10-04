package dev.folio.scanner.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

enum class PdfQuality(val edge: Int, val jpeg: Int) { ORIGINAL(Int.MAX_VALUE, 95), BALANCED(2400, 85), SMALL(1600, 65) }
data class PdfSource(val file: File, val pages: List<Int>, val rotations: List<Int> = List(pages.size) { 0 }, val password: String = "")

/** One-based page selection, in the user's explicit order. No implicit sorting. */
fun pageSelection(text: String, count: Int): List<Int> {
    require(count in 1..500) { "Choose a PDF with 1 to 500 pages." }
    if (text.isBlank()) return (1..count).toList()
    val result = text.split(',').flatMap { token ->
        val range = token.trim().split('-')
        require(range.size in 1..2) { "Use page numbers or ranges, such as 3,1-2." }
        val start = range[0].toIntOrNull() ?: error("Enter valid page numbers.")
        val end = if (range.size == 2) range[1].toIntOrNull() ?: error("Enter a valid range.") else start
        require(start in 1..count && end in start..count) { "Page range is outside this PDF." }
        (start..end).toList()
    }
    require(result.isNotEmpty() && result.size <= 500 && result.distinct().size == result.size) { "Choose each page once, up to 500 pages." }
    return result
}

class PdfEngine @Inject constructor() {
    fun read(file: File, password: String = ""): PdfDocument = PdfDocument(
        PdfReader(file.path, ReaderProperties().setPassword(password.toByteArray(Charsets.UTF_8))).setMemorySavingMode(true))

    fun count(file: File, password: String = ""): Int = read(file, password).use {
        require(it.numberOfPages in 1..500) { "Choose a PDF with 1 to 500 pages." }
        it.numberOfPages
    }

    private fun writer(file: File, password: String): PdfWriter {
        require(password.isEmpty() || password.length in 4..64) { "Use a password with 4 to 64 characters." }
        val properties = WriterProperties().setFullCompressionMode(true).setCompressionLevel(9)
        if (password.isNotEmpty()) properties.setStandardEncryption(password.toByteArray(Charsets.UTF_8),
            password.toByteArray(Charsets.UTF_8), EncryptionConstants.ALLOW_PRINTING or EncryptionConstants.ALLOW_COPY,
            EncryptionConstants.ENCRYPTION_AES_256)
        return PdfWriter(file.path, properties)
    }

    private fun metadata(pdf: PdfDocument, title: String) {
        pdf.documentInfo.setTitle(title).setCreator("Folio")
            .setMoreInfo("License", "iText Community, AGPL-3.0; Copyright iText Group NV. https://itextpdf.com")
        // Retain iText's producer metadata, including for copied/manipulated PDFs.
    }

    fun generate(images: List<File>, output: File, title: String, quality: PdfQuality = PdfQuality.ORIGINAL,
                 password: String = "", progress: (Int, Int) -> Unit = { _, _ -> }, layouts: List<dev.folio.scanner.data.PageLayout> = emptyList()) {
        require(layouts.isEmpty() || layouts.size == images.size)
        require(images.size in 1..500) { "Add 1 to 500 pages before generating a PDF." }
        PdfDocument(writer(output, password)).use { pdf ->
            metadata(pdf, title)
            images.forEachIndexed { index, file ->
                progress(index, images.size)
                val data = if (quality == PdfQuality.ORIGINAL) ImageDataFactory.create(file.path) else compressed(file, quality)
                // 150 dpi physical sizing preserves original aspect ratio and all embedded pixels.
                val physical = layouts.getOrNull(index)?.dimensions
                val page = pdf.addNewPage(if(physical==null) com.itextpdf.kernel.geom.PageSize(data.width * 72f / 150f, data.height * 72f / 150f)
                    else com.itextpdf.kernel.geom.PageSize((physical.first*72/25.4).toFloat(),(physical.second*72/25.4).toFloat()))
                val image = PdfImageXObject(data)
                val scale = if(layouts.getOrNull(index)?.fit=="Fill") maxOf(page.pageSize.width/data.width,page.pageSize.height/data.height) else minOf(page.pageSize.width/data.width,page.pageSize.height/data.height)
                val w=data.width*scale; val h=data.height*scale
                PdfCanvas(page).saveState().rectangle(0.0,0.0,page.pageSize.width.toDouble(),page.pageSize.height.toDouble()).clip().endPath()
                    .addXObjectFittedIntoRectangle(image,Rectangle((page.pageSize.width-w)/2,(page.pageSize.height-h)/2,w,h)).restoreState()
                image.flush(); page.flush()
            }
            progress(images.size, images.size)
        }
    }

    private fun compressed(file: File, quality: PdfQuality): com.itextpdf.io.image.ImageData {
        return compressed(file.readBytes(), quality)
    }
    private fun compressed(bytes: ByteArray, quality: PdfQuality): com.itextpdf.io.image.ImageData {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Page image is unreadable." }
        val options = BitmapFactory.Options().apply { while (maxOf(bounds.outWidth, bounds.outHeight) / (inSampleSize.coerceAtLeast(1) * 2) >= quality.edge) inSampleSize = inSampleSize.coerceAtLeast(1) * 2 }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val ratio = minOf(1f, quality.edge.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true)
        try {
            return java.io.ByteArrayOutputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, quality.jpeg, it)); ImageDataFactory.create(it.toByteArray()) }
        } finally { if (scaled !== bitmap) scaled.recycle(); bitmap.recycle() }
    }

    /** Copy PDF objects; imported vector text and page resolution remain intact. */
    fun combine(sources: List<PdfSource>, output: File, title: String, password: String = "",
                quality: PdfQuality = PdfQuality.ORIGINAL, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val total = sources.sumOf { it.pages.size }; require(total in 1..500) { "Choose 1 to 500 pages." }
        var done = 0
        PdfDocument(writer(output, password)).use { out ->
            metadata(out, title)
            sources.forEach { source ->
                require(source.rotations.size == source.pages.size)
                read(source.file, source.password).use { input ->
                    require(input.reader.isOpenedWithFullPermission) { "This PDF restricts editing. Use its owner password." }
                    val release = PageFlushingHelper(input)
                    source.pages.forEachIndexed { index, number ->
                        require(number in 1..input.numberOfPages)
                        require(source.rotations[index] in listOf(0, 90, 180, 270))
                        progress(done, total)
                        input.copyPagesTo(number, number, out)
                        out.getPage(out.numberOfPages).let {
                            it.setRotation((it.rotation + source.rotations[index]) % 360)
                            if (quality != PdfQuality.ORIGINAL) compressResources(it.resources.pdfObject, quality, mutableSetOf())
                            // Release copied image/font streams as well as the page dictionary.
                            it.flush(true)
                        }
                        release.releaseDeep(number)
                        done++
                    }
                }
            }
            progress(done, total)
        }
    }

    /** Copy original PDF objects and draw page-associated vector ink/text on the visible page. */
    fun editUtility(source:File,pages:List<UtilityPage>,output:File,title:String,progress:(Int,Int)->Unit={ _,_ -> }) {
        require(pages.size in 1..500)
        read(source).use { original -> PdfDocument(writer(output, "")).use { out ->
            metadata(out,title)
            pages.forEachIndexed { index,edit ->
                progress(index,pages.size)
                if(edit.replacement.isEmpty()) original.copyPagesTo(edit.source,edit.source,out)
                else read(File(edit.replacement)).use { it.copyPagesTo(1,1,out) }
                val page=out.getPage(out.numberOfPages)
                page.setRotation((page.rotation+edit.rotation)%360)
                page.setIgnorePageRotationForContent(true)
                val rect=page.pageSizeWithRotation
                val canvas=PdfCanvas(page.newContentStreamAfter(),page.resources,out)
                fun rgb(color:Int)=com.itextpdf.kernel.colors.DeviceRgb(android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color))
                edit.ink.forEach { ink ->
                    require(ink.width.isFinite() && ink.width in .001f.. .05f && ink.points.all { it.x in 0f..1f && it.y in 0f..1f })
                    canvas.saveState().setStrokeColor(rgb(ink.color)).setLineWidth(ink.width*minOf(rect.width,rect.height)).setLineCapStyle(1).setLineJoinStyle(1)
                    ink.points.forEachIndexed { i,p -> if(i==0) canvas.moveTo((p.x*rect.width).toDouble(),((1-p.y)*rect.height).toDouble()) else canvas.lineTo((p.x*rect.width).toDouble(),((1-p.y)*rect.height).toDouble()) }
                    if(ink.points.size==1) ink.points.single().let { canvas.lineTo((it.x*rect.width+.01).toDouble(),((1-it.y)*rect.height).toDouble()) }
                    canvas.stroke().restoreState()
                }
                if(edit.notes.isNotEmpty()) {
                    val font=com.itextpdf.kernel.font.PdfFontFactory.createFont("/system/fonts/Roboto-Regular.ttf",com.itextpdf.io.font.PdfEncodings.IDENTITY_H)
                    edit.notes.forEach { note ->
                        require(note.text.length<=4000 && note.x in 0f..1f && note.y in 0f..1f)
                        val fontSize=note.size*rect.width
                        note.text.lines().forEachIndexed { line,text ->
                            canvas.saveState().setFillColor(rgb(note.color)).beginText().setFontAndSize(font,fontSize)
                                .moveText((note.x*rect.width).toDouble(),((1-note.y)*rect.height-line*fontSize*1.3).toDouble()).showText(text).endText().restoreState()
                        }
                    }
                }
                page.flush(true)
            }
            progress(pages.size,pages.size)
        } }
    }

    private fun compressResources(resources: PdfDictionary, quality: PdfQuality, visited: MutableSet<PdfStream>, depth: Int = 0) {
        require(depth <= 32) { "PDF image resources are nested too deeply to compress safely." }
        resources.getAsDictionary(PdfName.XObject)?.values()?.forEach { objectValue ->
            val stream = objectValue as? PdfStream ?: return@forEach
            if (stream.isFlushed || !visited.add(stream)) return@forEach
            if (stream.getAsName(PdfName.Subtype) == PdfName.Form) {
                stream.getAsDictionary(PdfName.Resources)?.let { compressResources(it, quality, visited, depth + 1) }
            } else if (stream.getAsName(PdfName.Subtype) == PdfName.Image && stream.getAsName(PdfName.Filter) == PdfName.DCTDecode &&
                stream.get(PdfName.SMask) == null && stream.get(PdfName.Mask) == null && stream.get(PdfName.Decode) == null && stream.get(PdfName.DecodeParms) == null) {
                val source = stream.getBytes(false)
                val color = stream.getAsName(PdfName.ColorSpace)
                val profile = stream.getAsArray(PdfName.ColorSpace)?.takeIf { it.getAsName(0) == PdfName.ICCBased }?.getAsStream(1)
                val matchingProfile = profile?.let { ImageDataFactory.create(source).profile?.data?.contentEquals(it.getBytes()) } == true
                if (color !in listOf(PdfName.DeviceRGB, PdfName.DeviceGray) && !matchingProfile) return@forEach
                val data = compressed(source, quality)
                val replacement = PdfImageXObject(data).pdfObject
                if (replacement.getBytes(false).size < source.size) {
                    stream.clear(); stream.putAll(replacement); stream.setData(replacement.getBytes(false))
                }
                stream.flush()
            }
        }
    }

    /** Native rendering of one temporary page also supports encrypted PDFs on API 26. */
    fun render(file: File, page: Int, cache: File, password: String = "", edge: Int = 1400): Bitmap {
        val single = File.createTempFile("preview-", ".pdf", cache)
        try {
            read(file, password).use { input ->
                require(page in 1..input.numberOfPages)
                PdfDocument(PdfWriter(single.path)).use { input.copyPagesTo(page, page, it) }
            }
            PdfRenderer(ParcelFileDescriptor.open(single, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                renderer.openPage(0).use { source ->
                    val ratio = edge.toFloat() / maxOf(source.width, source.height)
                    val bitmap = Bitmap.createBitmap((source.width * ratio).roundToInt().coerceAtLeast(1), (source.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(Color.WHITE); source.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return bitmap }
                    catch (error: Throwable) { bitmap.recycle(); throw error }
                }
            }
        } finally { single.delete() }
    }
}

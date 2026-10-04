package dev.folio.scanner.pdf

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.*
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/** Reuses the prepared PDF, its physical page geometry and embedded images. No bitmap rerender. */
class FolioPrintAdapter(private val source:File,private val title:String):PrintDocumentAdapter() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var attributes:PrintAttributes?=null
    override fun onLayout(oldAttributes:PrintAttributes?,newAttributes:PrintAttributes,cancellationSignal:CancellationSignal,
                          callback:LayoutResultCallback,extras:Bundle?) {
        attributes=newAttributes
        val job=scope.launch {
            try {
                val count=withContext(Dispatchers.IO) { PdfEngine().read(source).use { it.numberOfPages } }
                ensureActive(); cancellationSignal.throwIfCanceled()
                callback.onLayoutFinished(PrintDocumentInfo.Builder("$title.pdf").setPageCount(count).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(),oldAttributes!=newAttributes)
            } catch(cancel:CancellationException) { callback.onLayoutCancelled() }
            catch(cancel:android.os.OperationCanceledException) { callback.onLayoutCancelled() }
            catch(error:Exception) { callback.onLayoutFailed("Could not read the printable PDF. Return to Folio and retry.") }
            catch(_:OutOfMemoryError) { callback.onLayoutFailed("Not enough memory. Print fewer pages and retry.") }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }
    override fun onWrite(pages:Array<out PageRange>,destination:ParcelFileDescriptor,cancellationSignal:CancellationSignal,callback:WriteResultCallback) {
        val current=requireNotNull(attributes)
        val job=scope.launch {
            try {
                val written=writePrintedPages(source,title,current,pages,destination,cancellationSignal)
                ensureActive(); cancellationSignal.throwIfCanceled(); callback.onWriteFinished(written)
            } catch(cancel:CancellationException) { callback.onWriteCancelled() }
            catch(cancel:android.os.OperationCanceledException) { callback.onWriteCancelled() }
            catch(error:Exception) { callback.onWriteFailed("Could not prepare printed pages. Return to Folio and retry.") }
            catch(_:OutOfMemoryError) { callback.onWriteFailed("Not enough memory. Print fewer pages and retry.") }
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }
    override fun onFinish() { scope.cancel(); source.delete() }
}

suspend fun printAttributes(file:File):PrintAttributes = withContext(Dispatchers.IO) {
    PdfEngine().read(file).use { pdf ->
        val first=pdf.getPage(1).pageSize
        val width=(first.width*1000/72).toInt().coerceAtLeast(1); val height=(first.height*1000/72).toInt().coerceAtLeast(1)
        // Android recognizes standard media IDs; a custom ID for A5 otherwise falls back to Letter.
        val standard=listOf(PrintAttributes.MediaSize.ISO_A4,PrintAttributes.MediaSize.ISO_A5,PrintAttributes.MediaSize.NA_LETTER,PrintAttributes.MediaSize.NA_LEGAL)
            .flatMap { listOf(it,it.asLandscape()) }.firstOrNull { kotlin.math.abs(it.widthMils-width)<=10 && kotlin.math.abs(it.heightMils-height)<=10 }
        PrintAttributes.Builder().setMediaSize(standard ?: PrintAttributes.MediaSize("folio-page","Document size",width,height))
            .setResolution(PrintAttributes.Resolution("folio","Document",300,300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
    }
}

internal suspend fun writePrintedPages(source:File,title:String,current:PrintAttributes,pages:Array<out PageRange>,destination:ParcelFileDescriptor,cancellationSignal:CancellationSignal):Array<PageRange> = withContext(Dispatchers.IO) {
                    val selected=mutableListOf<PageRange>(); val media=requireNotNull(current.mediaSize)
                    val width=media.widthMils*72f/1000; val height=media.heightMils*72f/1000
                    val margins=current.minMargins ?: PrintAttributes.Margins.NO_MARGINS
                    val target=Rectangle(margins.leftMils*72f/1000,margins.bottomMils*72f/1000,
                        width-(margins.leftMils+margins.rightMils)*72f/1000,height-(margins.topMils+margins.bottomMils)*72f/1000)
                    require(target.width>0 && target.height>0)
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        PdfEngine().read(source).use { input -> PdfDocument(PdfWriter(output).apply { setCloseStream(false) }).use { pdf ->
                            val release=PageFlushingHelper(input)
                            pdf.documentInfo.setTitle(title).setCreator("Folio")
                            pdf.documentInfo.setMoreInfo("License","iText Community, AGPL-3.0; Copyright iText Group NV. https://itextpdf.com")
                            for(index in 0 until input.numberOfPages) {
                                currentCoroutineContext().ensureActive(); cancellationSignal.throwIfCanceled()
                                if(pages.none { index in it.start..it.end }) continue
                                val page=input.getPage(index+1); val form=page.copyAsFormXObject(pdf)
                                val bounds=page.pageSize
                                val scale=minOf(target.width/bounds.width,target.height/bounds.height)
                                val canvas=PdfCanvas(pdf.addNewPage(com.itextpdf.kernel.geom.PageSize(width,height)))
                                canvas.addXObjectWithTransformationMatrix(form,scale,0f,0f,scale,
                                    target.x+(target.width-bounds.width*scale)/2-bounds.x*scale,
                                    target.y+(target.height-bounds.height*scale)/2-bounds.y*scale)
                                pdf.getLastPage().flush(true); release.releaseDeep(index+1); selected+=PageRange(index,index)
                            }
                            require(selected.isNotEmpty()) { "Choose at least one page." }
                        } }
                    }; selected.toTypedArray()
                }

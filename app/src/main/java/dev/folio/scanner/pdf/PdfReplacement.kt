package dev.folio.scanner.pdf

import dev.folio.scanner.data.PageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Replacement files stay in the existing utility session; original sources are read only. */
suspend fun PdfUtility.replacementTargetLayout(id:String,page:UtilityPage):PageLayout=withContext(Dispatchers.IO) {
    val copy=File.createTempFile("replacement-size-",".pdf",folder(id))
    try {
        engine.editUtility(File(folder(id),"input-0"),listOf(page.copy(ink=emptyList(),notes=emptyList())),copy,"Replacement size")
        engine.read(copy).use { pdf -> pdf.getPage(1).pageSizeWithRotation.let { PageLayout("Custom","Fit",it.width*25.4/72,it.height*25.4/72) } }
    } finally { copy.delete() }
}

suspend fun PdfUtility.replacementPdfPage(file:File,page:Int,password:String=""):Pair<File,PageLayout> = withContext(Dispatchers.IO) {
    val layout=engine.read(file,password).use { pdf ->
        require(pdf.reader.isOpenedWithFullPermission) { "Use this PDF's owner password to copy a page." }
        require(page in 1..pdf.numberOfPages) { "Choose a page in this PDF." }
        pdf.getPage(page).pageSizeWithRotation.let { PageLayout("Custom","Fit",it.width*25.4/72,it.height*25.4/72) }
    }
    val selected=File.createTempFile("selected-page-",".pdf",file.parentFile)
    val image=File(file.parentFile,"replace-${UUID.randomUUID()}.jpg")
    try {
        engine.combine(listOf(PdfSource(file,listOf(page),password=password)),selected,"Replacement page")
        val bitmap=engine.render(selected,1,file.parentFile!!,edge=3000)
        try { image.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)) } } finally { bitmap.recycle() }
        image to layout
    } catch(t:Throwable) { image.delete(); throw t } finally { selected.delete() }
}

/** Original PDF dimensions follow its visual rotation; matching always fits without stretching. */
fun replacementLayout(match:Boolean,target:PageLayout,selected:PageLayout,natural:PageLayout?,rotation:Int):PageLayout =
    if(match) target.copy(fit="Fit") else if(selected.size=="Original" && natural!=null) {
        if(rotation%180==0) natural else natural.copy(widthMm=natural.heightMm,heightMm=natural.widthMm)
    } else selected

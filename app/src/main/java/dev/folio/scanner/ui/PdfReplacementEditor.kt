@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.*
import dev.folio.scanner.pdf.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** Select one page because Replace changes exactly one existing utility page. */
@Composable
internal fun PdfReplacementPagePicker(file:File,model:LibraryViewModel,cancel:()->Unit,choose:(File,PageLayout)->Unit) {
    var password by remember { mutableStateOf("") }
    var enteredPassword by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var number by rememberSaveable(file.path) { mutableStateOf("1") }
    var error by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var ready by remember { mutableStateOf(false) }
    var renderedPage by remember { mutableIntStateOf(0) }
    val busy by model.busy.collectAsStateWithLifecycle()
    val page=number.toIntOrNull()?.takeIf { it in 1..count }
    LaunchedEffect(file.path,password) {
        count=0; error=""
        try { count=withContext(Dispatchers.IO) { model.utility.engine.read(file,password).use { require(it.reader.isOpenedWithFullPermission) { "Use this PDF's owner password to copy a page." }; it.numberOfPages } } }
        catch(t:CancellationException) { throw t } catch(t:Exception) { error="Could not open this PDF. For a protected PDF, enter its owner password." }
    }
    LaunchedEffect(file.path,page,password,count) {
        ready=false
        if(page!=null) try {
            val bitmap=withContext(Dispatchers.IO) { model.utility.engine.render(file,page,file.parentFile!!,password,edge=1400) }
            preview=bitmap; renderedPage=page; ready=true
        } catch(t:CancellationException) { throw t } catch(t:Exception) { error="Could not preview this PDF page." }
    }
    Scaffold(topBar={ TopAppBar(title={ Text("Choose PDF page") },navigationIcon={ TextButton(onClick=cancel,enabled=!busy) { Text("Cancel") } }) },bottomBar={
        Column(Modifier.navigationBarsPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(count>0) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IconButton(onClick={ number=((page ?: 1)-1).toString() },enabled=page!=null && page>1) { Icon(Icons.Outlined.ChevronLeft,"Previous PDF page") }
                OutlinedTextField(number,{ number=it.filter(Char::isDigit).take(4) },label={ Text("Page of $count") },singleLine=true,isError=page==null,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),modifier=Modifier.weight(1f))
                IconButton(onClick={ number=((page ?: 0)+1).toString() },enabled=page!=null && page<count) { Icon(Icons.Outlined.ChevronRight,"Next PDF page") }
            }
            if(error.isNotEmpty()) {
                Text(error,color=MaterialTheme.colorScheme.error)
                OutlinedTextField(enteredPassword,{ enteredPassword=it },label={ Text("PDF owner password") },singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                OutlinedButton(onClick={ password=enteredPassword },enabled=enteredPassword.isNotEmpty() && enteredPassword!=password) { Text("Open protected PDF") }
            }
            Text("Only the chosen page replaces the current page. It is rendered as an image for crop and filter editing; the source PDF stays unchanged.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={ val chosen=page ?: return@Button; model.run { val result=model.utility.replacementPdfPage(file,chosen,password); choose(result.first,result.second) } },enabled=ready && page!=null && renderedPage==page && !busy,modifier=Modifier.fillMaxWidth()) { Text("Use selected PDF page") }
        }
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding).padding(12.dp),contentAlignment=Alignment.Center) {
        if(ready) preview?.let { DocumentCanvas(it,"Replacement PDF page $page preview",Modifier.fillMaxSize()) } else if(error.isEmpty()) CircularProgressIndicator()
    } }
}

/** Reuses scanner crop, enhancement and physical page canvas without creating Folio library data. */
@Composable
internal fun PdfReplacementEditor(file:File,model:LibraryViewModel,cancel:()->Unit,target:PageLayout,natural:PageLayout?=null,accept:(File,PageLayout)->Unit) {
    var cropping by rememberSaveable(file.path) { mutableStateOf(natural==null) }
    var crop by rememberSaveable(file.path) { mutableStateOf(encodeCorners(Geometry.full)) }
    var enhancement by rememberSaveable(file.path) { mutableStateOf(Enhancement().encode()) }
    val settings=Enhancement.decode(enhancement)
    var rotation by rememberSaveable(file.path) { mutableIntStateOf(0) }
    var paperSize by rememberSaveable(file.path) { mutableStateOf("Original") }
    var paperFit by rememberSaveable(file.path) { mutableStateOf("Fit") }
    var paperWidth by rememberSaveable(file.path) { mutableDoubleStateOf(0.0) }
    var paperHeight by rememberSaveable(file.path) { mutableDoubleStateOf(0.0) }
    var match by rememberSaveable(file.path) { mutableStateOf(true) }
    val selected=PageLayout(paperSize,paperFit,paperWidth,paperHeight)
    val layout=replacementLayout(match,target,selected,natural,rotation)
    fun setLayout(value:PageLayout) { paperSize=value.size; paperFit=value.fit; paperWidth=value.widthMm; paperHeight=value.heightMm }
    var detectedReady by rememberSaveable(file.path) { mutableStateOf(natural!=null) }
    var replacementError by remember { mutableStateOf("") }
    val processing=remember(file.path) { Mutex() }
    var preview by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var contentPreview by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var previewReady by remember { mutableStateOf(false) }
    var paperSheet by rememberSaveable { mutableStateOf(false) }
    var filters by rememberSaveable { mutableStateOf(false) }
    val busy by model.busy.collectAsStateWithLifecycle()
    fun process(limit:Int,applyLayout:Boolean=true):Bitmap {
        val source=model.pipeline.correct(file,decodeCorners(crop),limit)
        try {
            val processed=model.pipeline.enhance(source,settings,rotation)
            if(!applyLayout) return processed
            try { val canvas=model.pipeline.pageCanvas(processed,layout); if(canvas!==processed) processed.recycle(); return canvas }
            catch(t:Throwable) { processed.recycle(); throw t }
        } finally { source.recycle() }
    }
    LaunchedEffect(file.path) {
        if(!detectedReady) try {
            val image=withContext(Dispatchers.IO) { model.pipeline.decode(file,1600) }
            try { val detected=withContext(Dispatchers.Default) { model.pipeline.detect(image) }; if(detected!=null) crop=encodeCorners(detected) } finally { image.recycle() }
            detectedReady=true
        } catch(t:CancellationException) { throw t } catch(t:Exception) { replacementError="Could not open this replacement image. Choose another file." }
    }
    if(!detectedReady) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) { if(replacementError.isEmpty()) CircularProgressIndicator() else Text(replacementError); OutlinedButton(onClick=cancel) { Text("Cancel") } }; return }
    if(cropping) { CropScreen(file.name,model,cancel,{},utilityImage=file,utilityCrop=crop,utilitySave={ crop=encodeCorners(it); cropping=false }); return }
    val previewKey=listOf(crop,settings,rotation,layout)
    var renderedKey by remember { mutableStateOf<List<Any>?>(null) }
    LaunchedEffect(file.path,previewKey) {
        previewReady=false; replacementError=""; delay(120)
        var loaded:Bitmap?=null
        var content:Bitmap?=null
        try {
            withContext(Dispatchers.IO) { processing.withLock { content=process(1600,false); loaded=model.pipeline.pageCanvas(content!!,layout) } }
            // Compose can still draw the previous display list. Let GC reclaim displayed previews.
            preview=loaded; contentPreview=content; loaded=null; content=null
            renderedKey=previewKey; previewReady=true
        } catch(t:CancellationException) { throw t } catch(t:Exception) { replacementError="Could not process this image. Adjust the settings or choose another file." }
        finally { loaded?.recycle(); if(content!==loaded) content?.recycle() }
    }
    if(paperSheet) PageSizeSheet(selected,contentPreview,0,{ paperSheet=false }) { setLayout(it); paperSheet=false }
    if(filters) ModalBottomSheet(onDismissRequest={ filters=false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text("Image filter",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge); TextButton(onClick={ filters=false }) { Text("Done") } }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { Enhancement.presets.forEach { preset -> FilterChip(settings.preset==preset,{ enhancement=settings.copy(preset=preset).encode() },label={ Text(preset) }) } }
            listOf("Brightness" to (settings.brightness to (-80f..80f)),"Contrast" to (settings.contrast to (.5f..2f)),"Saturation" to (settings.saturation to (0f..2f)),"Sharpness" to (settings.sharpness to (0f..2f)),"Threshold" to (settings.threshold to (0f..30f)),"Shadow removal" to (settings.shadow to (0f..1f))).forEach { (label,values) ->
                Text(label,style=MaterialTheme.typography.labelLarge)
                Slider(values.first.toFloat(),{ value -> enhancement=when(label) { "Brightness" -> settings.copy(brightness=value.toDouble()); "Contrast" -> settings.copy(contrast=value.toDouble()); "Saturation" -> settings.copy(saturation=value.toDouble()); "Sharpness" -> settings.copy(sharpness=value.toDouble()); "Threshold" -> settings.copy(threshold=value.toDouble()); else -> settings.copy(shadow=value.toDouble()) }.encode() },valueRange=values.second,modifier=Modifier.semantics { contentDescription=label })
            }
        }
    }
    Scaffold(topBar={ TopAppBar(title={ Text("Edit replacement",style=MaterialTheme.typography.titleMedium) },navigationIcon={ TextButton(onClick=cancel,enabled=!busy) { Text("Cancel") } },actions={
        Button(onClick={ model.run {
            val output=File(file.parentFile,"processed-${UUID.randomUUID()}.jpg")
            try { withContext(Dispatchers.IO) { processing.withLock { val canvas=process(8192); try { output.outputStream().use { check(canvas.compress(Bitmap.CompressFormat.JPEG,95,it)) } } finally { canvas.recycle() } } }; accept(output,layout) }
            catch(t:Throwable) { output.delete(); throw t }
        } },enabled=!busy && previewReady && renderedKey==previewKey && replacementError.isEmpty(),modifier=Modifier.padding(end=8.dp)) { Text("Use page") }
    }) },bottomBar={
        Surface(color=MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha=.94f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shape=MaterialTheme.shapes.large) {
            Column(Modifier.navigationBarsPadding().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    val dimensions=layout.dimensions
                    Text(if(dimensions!=null) "%.1f × %.1f mm · %s".format(java.util.Locale.US,dimensions.first,dimensions.second,layout.fit) else "Original / Auto · ${layout.fit}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick={ paperSheet=true },enabled=!match && !busy) { Icon(Icons.Outlined.AspectRatio,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Page size") }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={ cropping=true },enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { Icon(Icons.Outlined.Crop,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Crop") }
                    OutlinedButton(onClick={ rotation=(rotation+90)%360 },enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { Icon(Icons.Outlined.Rotate90DegreesCw,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Rotate") }
                    OutlinedButton(onClick={ filters=true },enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=48.dp),contentPadding=PaddingValues(horizontal=8.dp,vertical=8.dp)) { Icon(Icons.Outlined.Tune,null,Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Filter") }
                }
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) { Text("Match Page Size",style=MaterialTheme.typography.titleSmall); Text(if(match) "Fit to the current PDF page" else "Use original or selected dimensions",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(match,{ match=it },enabled=!busy,modifier=Modifier.semantics { contentDescription="Match Page Size" })
                }
            }
        }
    }) { padding -> Box(Modifier.fillMaxSize().padding(padding).padding(8.dp).semantics { contentDescription="Replacement page sizing"; stateDescription="${if(match) "Matched" else "Natural"} · ${layout.dimensions} · $rotation · ${settings.preset} · preview ${preview?.width}x${preview?.height}" },contentAlignment=Alignment.Center) {
        preview?.let { DocumentCanvas(it,"Replacement preview",Modifier.fillMaxSize()) } ?: CircularProgressIndicator()
        if(preview!=null && renderedKey!=previewKey) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        if(replacementError.isNotEmpty()) Text(replacementError,color=MaterialTheme.colorScheme.error)
    } }
}

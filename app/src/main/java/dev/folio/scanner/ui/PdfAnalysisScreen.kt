@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.pdf.pdfFileStem
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable internal fun PdfAnalysisScreen(model:LibraryViewModel,initialTool:String,initialSession:String="",back:()->Unit) {
    val utility=model.utility;val context=LocalContext.current
    var tool by rememberSaveable(initialTool) {mutableStateOf(initialTool)}
    var toolsMenu by remember {mutableStateOf(false)}
    var diagnostics by remember {mutableStateOf(false)}
    var discard by remember {mutableStateOf(false)}
    var session by rememberSaveable {mutableStateOf(initialSession)}
    var source by rememberSaveable {mutableStateOf(initialSession.isEmpty())}
    var managed by rememberSaveable {mutableStateOf(false)}
    var input by rememberSaveable {mutableStateOf("")};var password by remember {mutableStateOf("")}
    var pageIndex by rememberSaveable {mutableIntStateOf(0)}
    var textTab by rememberSaveable {mutableStateOf(false)};var fullscreen by rememberSaveable {mutableStateOf(false)}
    val compact=with(androidx.compose.ui.platform.LocalDensity.current) {androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()}<480.dp
    var ocrOptions by rememberSaveable {mutableStateOf(false)}
    var ocrMode by rememberSaveable {mutableStateOf(OcrMode.LAYOUT_AWARE)}
    var showBounds by rememberSaveable {mutableStateOf(true)}
    var selected by remember {mutableStateOf<AnalysisRegion?>(null)}
    val busy by model.busy.collectAsStateWithLifecycle()
    var leaving by remember {mutableStateOf(false)}
    if(leaving) {
        BackHandler { }
        Scaffold {padding ->Column(Modifier.fillMaxSize().padding(padding),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            CircularProgressIndicator();Text("Cancelling safely…",Modifier.padding(16.dp))
        }}
        return
    }
    val jobs by remember(session) {utility.work.getWorkInfosByTagFlow("analysis-$session")}.collectAsStateWithLifecycle(emptyList())
    val exports by remember(session) {utility.work.getWorkInfosByTagFlow("analysis-export-$session")}.collectAsStateWithLifecycle(emptyList())
    val active=jobs.firstOrNull { !it.state.isFinished };val exportActive=exports.firstOrNull {!it.state.isFinished}
    val info by produceState<JSONObject?>(null,session,jobs) {value=if(session.isEmpty()) null else withContext(Dispatchers.IO) {try {utility.session(session)} catch(_:java.io.FileNotFoundException) {null}}}
    val count=info?.getJSONArray("counts")?.getInt(0) ?: 0
    val title=info?.getJSONArray("names")?.getString(0)?.let(::pdfFileStem).orEmpty()
    val page by produceState<AnalysisPage?>(null,session,pageIndex,jobs) {
        value=if(session.isEmpty()) null else withContext(Dispatchers.IO) {runCatching {AnalysisPage.parse(JSONObject(ocrResultFile(utility.folder(session,create=false),pageIndex).readText()))}.getOrNull()}
    }
    // Animated/lazy content can outlive this composition while the next page loads.
    val displayedPage=page
    val completePages by produceState(false,session,count,jobs,tool) {
        value=session.isNotEmpty() && count>0 && withContext(Dispatchers.IO) {(0 until count).all {index ->
            (if(tool=="ocr") ocrResultFile(utility.folder(session,create=false),index) else File(utility.folder(session,create=false),"analysis-$index.json")).isFile
        }}
    }
    val ready=active==null && completePages
    LaunchedEffect(session,info?.optString("ocrMode")) {ocrMode=runCatching {OcrMode.valueOf(info?.optString("ocrMode",OcrMode.LAYOUT_AWARE.name) ?: OcrMode.LAYOUT_AWARE.name)}.getOrDefault(OcrMode.LAYOUT_AWARE)}
    val exportRequest by produceState<JSONObject?>(null,session,exports) {value=withContext(Dispatchers.IO) {runCatching {JSONObject(File(utility.folder(session,create=false),"analysis-export.json").readText())}.getOrNull()}}
    val currentExport=exports.firstOrNull {it.id.toString()==exportRequest?.optString("work")}
    fun start(id:String) {session=id;pageIndex=0;utility.save(id,utility.session(id).put("kind","analysis").put("analysisTool",tool));if(tool=="ocr") utility.analyze(id,mode=ocrMode,replaceOcr=true) else utility.analyze(id,mode=OcrMode.LAYOUT_AWARE)}
    val picker=rememberLauncherForActivityResult(PdfInputPicker()) {uris ->uris.firstOrNull()?.let {input=it.toString()}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {uri ->if(uri!=null) model.run {withContext(Dispatchers.IO) {utility.exportAnalysis(session,uri,"text")}}}
    fun copy(text:String) {if(text.length>200000) {model.error.value="Too much text for the clipboard. Export a TXT file instead.";return};context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Folio PDF text",text))}
    fun close() {if(fullscreen) fullscreen=false else if(session.isNotEmpty()) discard=true else back()}
    BackHandler {close()}
    val screenTitle=when(tool) {"figures"->"Images and figures";"word"->"PDF to Word";else->"OCR PDF"}
    @Composable fun OcrControls() {
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OcrMode.entries.forEach {mode ->FilterChip(ocrMode==mode,{ocrMode=mode},enabled=active==null && exportActive==null,label={Text(mode.label)})}
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick={ocrOptions=false;selected=null;utility.analyze(session,mode=ocrMode,page=pageIndex,rerun=true,replaceOcr=true)},enabled=count>0 && active==null && exportActive==null && !busy) {Text("OCR Current Page")}
                        Button(onClick={ocrOptions=false;selected=null;utility.analyze(session,mode=ocrMode,rerun=true,replaceOcr=true)},enabled=count>0 && active==null && exportActive==null && !busy) {Text("OCR Entire Document")}
                    }
    }
    Scaffold(topBar={TopAppBar(title={Text(if(fullscreen) "Extracted text" else screenTitle)},navigationIcon={IconButton(onClick=::close) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back")}},actions={
        if(displayedPage!=null && tool=="ocr") IconButton(onClick={copy(displayedPage.text)}) {Icon(Icons.Outlined.ContentCopy,"Copy page text")}
        if(displayedPage!=null && tool=="ocr") IconButton(onClick={fullscreen=!fullscreen;textTab=true}) {Icon(if(fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,"Fullscreen text")}
        if(session.isNotEmpty()) Box {
            IconButton(onClick={toolsMenu=true}) {Icon(Icons.Outlined.MoreVert,"Analysis tools")}
            FolioOverflowMenu(toolsMenu,{toolsMenu=false},screenTitle) {

                listOf("OCR PDF" to "ocr","Images and figures" to "figures","PDF to Word" to "word").forEach {(label,key) ->FolioMenuItem(label=label,enabled=active==null && exportActive==null,onClick={tool=key;fullscreen=false;toolsMenu=false;model.run {withContext(Dispatchers.IO) {utility.save(session,utility.session(session).put("analysisTool",key));if(key!="ocr") utility.analyze(session,mode=OcrMode.LAYOUT_AWARE)}}})}
                if(dev.folio.scanner.BuildConfig.DEBUG) FolioMenuItem(label="Execution details",onClick={toolsMenu=false;diagnostics=true})
                FolioMenuItem(label="Discard analysis session",enabled=active==null && exportActive==null,onClick={toolsMenu=false;discard=true})
            }
        }
    })}) {padding ->
        Column(Modifier.fillMaxSize().padding(padding),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(session.isEmpty()) Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(when(tool) {"figures"->"Keep the complete figure";"word"->"Make your PDF editable";else->"Read your PDF offline"},style=MaterialTheme.typography.headlineSmall)
                Text(when(tool) {"figures"->"Original images where possible, vector PDF for self-contained vector figures, high-resolution PNG for composite regions.";"word"->"Native text and on-device OCR become editable paragraphs, tables and figures in a Word document.";else->"Native PDF text is preserved. Scanned regions use document layout and on-device OCR."},color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick={source=true}) {Text("Choose PDF")}
            } else {
                if(active!=null || exportActive!=null) {
                    val job=active ?: exportActive!!
                    Text(job.progress.getString("stage") ?: "Exporting…",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                    val total=job.progress.getInt("total",0)
                    if(total>0) Text("${job.progress.getInt("done",0)} of $total",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=16.dp))
                    TextButton(onClick={utility.work.cancelWorkById(job.id)}) {Text("Cancel processing")}
                }
                val failure=jobs.firstOrNull {it.id.toString()==info?.optString("analysisWorkId") && it.state in listOf(androidx.work.WorkInfo.State.FAILED,androidx.work.WorkInfo.State.CANCELLED)}
                if(failure!=null && active==null) Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(failure.outputData.getString("error") ?: "Analysis interrupted. Completed pages are retained.",Modifier.weight(1f),color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={utility.analyze(session,true)}) {Text("Retry CPU")}
                }
                if(active==null && !ready && failure==null) TextButton(onClick={utility.analyze(session,true)}) {Text("Resume analysis")}
                if(exportRequest?.optString("state")=="complete" && exportActive==null) {
                    Text("Export verified in device storage",Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.primary)
                    if(exportRequest?.optString("type")=="word") Text("${exportRequest!!.optInt("paragraphs")} paragraphs · ${exportRequest!!.optInt("tables")} tables · ${exportRequest!!.optInt("figuresCount")} figures",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                    exportRequest?.optJSONArray("warnings")?.let {warnings ->if(warnings.length()>0) Text("${warnings.length()} conversion notes: ${warnings.getString(0)}",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)}
                } else if(exportRequest!=null && exportActive==null) Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(currentExport?.outputData?.getString("error") ?: "Export interrupted. Completed files are retained.",Modifier.weight(1f),color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={model.run {withContext(Dispatchers.IO) {utility.retryAnalysisExport(session)}}}) {Text("Retry export")}
                }
                if(tool=="figures") FigureGallery(model,session,count,ready,exportActive!=null,Modifier.weight(1f)) else if(tool=="word") WordConversionPanel(model,session,count,title,ready,exportActive!=null,Modifier.weight(1f)) else {
                if(!fullscreen && compact) Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick={ocrOptions=true}) {Text("OCR options")}
                    displayedPage?.let {Text("Page result: ${it.mode.label}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))}
                }
                if(!fullscreen && !compact) Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    OcrControls()
                    displayedPage?.let {Text("Page result: ${it.mode.label}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                }
                if(!fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={pageIndex--},enabled=pageIndex>0) {Icon(Icons.Outlined.ChevronLeft,"Previous page")}
                    Text("${pageIndex+1} of $count",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                    IconButton(onClick={pageIndex++},enabled=pageIndex+1<count) {Icon(Icons.Outlined.ChevronRight,"Next page")}
                    FilterChip(!textTab,{textTab=false},label={Text("Page")});Spacer(Modifier.width(8.dp));FilterChip(textTab,{textTab=true},label={Text("Text")})
                }
                if(!fullscreen && !textTab && !compact) Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {Switch(showBounds,{showBounds=it});Spacer(Modifier.width(8.dp));Text(if(displayedPage?.mode==OcrMode.FULL_PAGE) "Show text boundaries" else "Show layout boundaries",style=MaterialTheme.typography.bodySmall)}
                if(displayedPage!=null) {
                    AnimatedContent(textTab || fullscreen,Modifier.weight(1f),transitionSpec={fadeIn(tween(160)) togetherWith fadeOut(tween(120))},label="Analysis view") {reading ->
                    if(reading) SelectionContainer(Modifier.fillMaxSize()) {LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        if(displayedPage.text.isBlank()) item {Text("No readable text found on this page.")}
                        items(displayedPage.regions.filter {it.text.isNotBlank()}.sortedBy {it.order}) {region ->Text(region.text)}
                    }} else AnalysisOverlay(File(utility.folder(session,create=false),"analysis-$pageIndex.jpg"),displayedPage,Modifier.fillMaxSize(),showBounds) {selected=it}
                    }
                } else Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {Text(if(active==null) "Start or retry analysis to read this page." else "This page is being prepared…",Modifier.padding(24.dp))}
                if(!fullscreen) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={model.run {val text=withContext(Dispatchers.IO) {buildString {repeat(count) {i ->append(AnalysisPage.parse(JSONObject(ocrResultFile(utility.folder(session,create=false),i).readText())).text);append("\n\n");require(length<=200000) {"Too much text for the clipboard. Export TXT instead."}}}};copy(text)}},enabled=ready) {Text("Copy all text")}
                    Button(onClick={export.launch("$title.txt")},enabled=ready && exportActive==null) {Text("Export TXT")}
                }
                }
            }
        }
    }
    if(ocrOptions) ModalBottomSheet(onDismissRequest={ocrOptions=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("OCR options",style=MaterialTheme.typography.titleMedium)
            OcrControls()
            Row(verticalAlignment=Alignment.CenterVertically) {Switch(showBounds,{showBounds=it});Spacer(Modifier.width(8.dp));Text("Show boundaries",style=MaterialTheme.typography.bodyMedium)}
            TextButton(onClick={ocrOptions=false},modifier=Modifier.align(Alignment.End)) {Text("Done")}
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("Leave with unsaved changes?")},text={Text("Remove this temporary PDF copy and its analysis results. Your source PDF, Folio documents and exported files will remain unchanged.")},confirmButton={TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick=leave@{if(leaving) return@leave;val id=session;leaving=true;model.run {try {utility.cancelAndDiscard(id);discard=false;session="";back()} finally {leaving=false}}},enabled=!busy) {Text(if(active!=null || exportActive!=null) "Discard and Leave" else "Discard Changes and Leave")}},dismissButton={TextButton(onClick={discard=false}) {Text(if(active!=null || exportActive!=null) "Continue Processing" else "Continue Editing")}})
    if(diagnostics) AlertDialog(onDismissRequest={diagnostics=false},title={Text("Execution details")},text={SelectionContainer {Text(page?.json()?.optString("diagnostics").orEmpty().ifBlank {"Execution details are available after a page is analyzed."},Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()))}},confirmButton={TextButton(onClick={diagnostics=false}) {Text("Close")}})
    if(source) PdfSourceDialog(screenTitle,{source=false;picker.launch(arrayOf("application/pdf"))},{source=false;managed=true},{source=false})
    if(managed) ManagedPdfChooser(model,{managed=false}) {id ->model.run {val chosen=utility.openManaged(id);managed=false;start(chosen)}}
    if(input.isNotEmpty()) AlertDialog(onDismissRequest={input="";password=""},title={Text("Open PDF")},text={OutlinedTextField(password,{password=it},label={Text("Owner password, if needed")},singleLine=true,visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())},confirmButton={Button(onClick={model.run {val chosen=utility.open("analysis",listOf(android.net.Uri.parse(input)),password);input="";password="";start(chosen)}},enabled=!busy) {Text("Open")}},dismissButton={TextButton(onClick={input="";password=""}) {Text("Cancel")}})
    selected?.let {region ->ModalBottomSheet(onDismissRequest={selected=null}) {Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(region.label.replace('_',' '),style=MaterialTheme.typography.titleMedium)
        SelectionContainer {Text(region.text.ifBlank {"No text in this region."},Modifier.heightIn(max=280.dp).verticalScroll(rememberScrollState()))}
        OutlinedButton(onClick={copy(region.text)},enabled=region.text.isNotBlank()) {Text("Copy region text")}
        Spacer(Modifier.navigationBarsPadding())
    }}}
}

@Composable internal fun AnalysisOverlay(file:File,page:AnalysisPage,modifier:Modifier=Modifier,showBounds:Boolean=true,select:(AnalysisRegion)->Unit) {
    // Compose's retained render nodes may draw a previous image after disposal; UI bitmaps use GC.
    val bitmap by produceState<android.graphics.Bitmap?>(null,file.path) {value=null;value=withContext(Dispatchers.IO) {BitmapFactory.decodeFile(file.path)}}
    val color=MaterialTheme.colorScheme.primary
    val labelBackground=MaterialTheme.colorScheme.primaryContainer
    val labelColor=MaterialTheme.colorScheme.onPrimaryContainer
    Box(modifier.pointerInput(page) {detectTapGestures {position ->val fit=FitTransform.create(page.width,page.height,size.width.toFloat(),size.height.toFloat());page.regions.filter {fit.map(it.box).contains(position.x,position.y)}.minByOrNull {it.box.width*it.box.height}?.let(select)}}) {
        bitmap?.let {Image(it.asImageBitmap(),if(page.mode==OcrMode.FULL_PAGE) "PDF page with text regions" else "PDF page with layout regions",Modifier.fillMaxSize())}
        if(showBounds) Canvas(Modifier.fillMaxSize()) {val fit=FitTransform.create(page.width,page.height,size.width,size.height)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {this.color=labelColor.toArgb();textSize=10.dp.toPx()}
            page.regions.forEach {val b=fit.map(it.box);drawRect(color,Offset(b.left,b.top),Size(b.width,b.height),style=Stroke(1.5.dp.toPx()))
                if(page.mode==OcrMode.LAYOUT_AWARE) {
                val label=it.label.replace('_',' ').take(22);val width=paint.measureText(label)+8.dp.toPx();val top=b.top.coerceAtLeast(0f)
                drawRect(labelBackground,Offset(b.left.coerceAtLeast(0f),top),Size(width,15.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(label,b.left.coerceAtLeast(0f)+4.dp.toPx(),top+11.dp.toPx(),paint)
                }
            }
        }
    }
}

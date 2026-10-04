@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import dev.folio.scanner.pdf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

@Composable
fun PdfWorkspace(documentId:String?,model:LibraryViewModel,back:()->Unit,open:(String)->Unit) {
    val utility=model.utility
    val library by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val jobs by remember { utility.work.getWorkInfosByTagFlow("pdf-utility") }.collectAsStateWithLifecycle(emptyList())
    var sessionId by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var password by remember { mutableStateOf("") }
    var passwordDialog by rememberSaveable { mutableStateOf(false) }
    var chooseDocs by rememberSaveable { mutableStateOf(false) }
    var addingMerge by rememberSaveable { mutableStateOf(false) }
    var changingFolder by rememberSaveable { mutableStateOf(false) }
    var folderRevision by remember { mutableIntStateOf(0) }
    var title by rememberSaveable { mutableStateOf("") }
    var ranges by rememberSaveable { mutableStateOf("") }
    var quality by rememberSaveable { mutableStateOf(PdfQuality.ORIGINAL) }
    var revision by remember { mutableIntStateOf(0) }
    var choosingExport by rememberSaveable { mutableStateOf(false) }
    var unsaved by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(emptyList<Pair<String,String>>()) }
    LaunchedEffect(jobs,sessionId,revision) { pending=withContext(Dispatchers.IO) { utility.pending().mapNotNull { id -> runCatching { id to utility.session(id).getString("kind") }.getOrNull() } } }
    val loadedSession by produceState<org.json.JSONObject?>(null,sessionId,revision,jobs) { val requested=sessionId; value=if(requested.isBlank()) null else try { withContext(Dispatchers.IO) { utility.session(requested).put("_session",requested) } } catch(t:kotlinx.coroutines.CancellationException) { throw t } catch(_:java.io.FileNotFoundException) { sessionId=""; null } }
    val session=loadedSession?.takeIf { it.optString("_session")==sessionId }
    val sessionJobs=jobs.filter { "utility-$sessionId" in it.tags }
    val active=if(session?.has("workId")==true) sessionJobs.firstOrNull { it.id.toString()==session.getString("workId") } else sessionJobs.firstOrNull { !it.state.isFinished } ?: sessionJobs.firstOrNull()
    val working=active?.state?.isFinished==false || (session?.optString("state")=="pending" && active==null)
    LaunchedEffect(active?.state,session?.optString("state")) { if(session?.optString("state")=="complete" || active?.state==WorkInfo.State.SUCCEEDED) { sessionId=""; choosingExport=false; completed=true; revision++ } else if(active?.state in listOf(WorkInfo.State.FAILED,WorkInfo.State.CANCELLED)) choosingExport=true }
    fun close() { if(working) { model.error.value="Cancel or finish the export before leaving."; return }; if(sessionId.isNotEmpty()) model.run { val discardId=sessionId; val dirty=withContext(Dispatchers.IO) { utility.session(discardId).optBoolean("dirty") }; if(dirty) unsaved=true else { pending=pending.filter { it.first!=discardId }; sessionId=""; utility.discard(discardId) } } else back() }
    BackHandler { close() }
    val destinationType=if(kind in listOf("split","raster")) "groups" else "pdf"
    val rememberedFolder by produceState<Uri?>(null,destinationType,folderRevision) { value=withContext(Dispatchers.IO) { utility.rememberedDestination(destinationType) } }
    val folderLabel by produceState("",rememberedFolder) { value=rememberedFolder?.let { withContext(Dispatchers.IO) { runCatching { utility.name(it) }.getOrDefault("Selected folder") } }.orEmpty() }
    fun acceptInput() { val inputs=picked.map(Uri::parse); val secret=password; model.run {
        if(addingMerge) utility.addMergeInputs(sessionId,inputs,secret) else sessionId=utility.open(kind,inputs,secret)
        val j=utility.session(sessionId); val names=j.getJSONArray("names")
        title=when(kind) { "merge" -> j.getJSONArray("order").let { order -> (0 until order.length()).joinToString(" ") { pdfFileStem(names.getString(order.getInt(it))) } }+" merged"; "edit" -> pdfFileStem(names.getString(0))+" edited"; else -> pdfFileStem(names.getString(0)) }
        if(!addingMerge) { ranges=""; quality=PdfQuality.ORIGINAL }
        addingMerge=false; passwordDialog=false; password=""; picked=emptyList(); revision++
    } }
    val pdfInput=rememberLauncherForActivityResult(PdfInputPicker()) { uris -> if(uris.isNotEmpty()) { picked=uris.map { it.toString() }; passwordDialog=true } }
    val mergeInput=rememberLauncherForActivityResult(PdfInputPicker(true)) { uris -> if(uris.isNotEmpty()) { picked=uris.map { it.toString() }; passwordDialog=true } }
    val imageInput=rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris -> if(uris.isNotEmpty()) { picked=uris.map { it.toString() }; acceptInput() } }
    fun exportFolder(folder:Uri) { val id=sessionId; val outputTitle=title; val outputRanges=ranges; val tree=kind in listOf("split","raster"); model.run {
        val target=if(tree) folder else utility.destinationFile(folder,outputTitle)
        utility.enqueue(id,target,tree,outputTitle,outputRanges); choosingExport=false; unsaved=false; revision++
    } }
    val folderDestination=rememberLauncherForActivityResult(PdfDestinationPicker()) { uri -> if(uri!=null) { model.run { val retained=withContext(Dispatchers.IO) { utility.rememberDestination(destinationType,uri) }; if(!retained) model.error.value="This provider granted temporary access only. Choose a folder again for your next export."; folderRevision++ }; if(!changingFolder) exportFolder(uri); changingFolder=false } }
    fun chooseDestination() { model.run { val folder=withContext(Dispatchers.IO) { utility.rememberedDestination(destinationType) }; if(folder!=null) exportFolder(folder) else { changingFolder=false; folderDestination.launch(documentsLocation) } } }
    if(sessionId.isNotEmpty() && session?.getString("kind")=="edit" && session.optString("state")!="complete" && active?.state!=WorkInfo.State.SUCCEEDED && !choosingExport && !working) {
        val pageState=session.getJSONArray("pages").let { a -> List(a.length()) { UtilityPage.from(a.getJSONObject(it)) } }
        PdfUtilityEditor(sessionId,pageState,model,::close,{ choosingExport=true },{ revision++ })
    } else Scaffold(topBar={ TopAppBar(title={ Text("PDF workspace") },navigationIcon={ IconButton(onClick=::close,enabled=!working) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(sessionId.isEmpty()) {
                if(completed) item { Text("Export verified in device storage",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleSmall) }
                item { Text("Files in. Files out.",style=MaterialTheme.typography.headlineSmall); Text("Choose files from your device and export to a location you choose. Your Folio library stays separate.",Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant) }
                items(listOf("Split PDF" to "split","Merge PDF" to "merge","Image to PDF" to "images","PDF to Image" to "raster","Edit PDF" to "edit","Generate PDF" to "generate")) { (label,key) ->
                    Surface(onClick={ kind=key; addingMerge=false; when(key) { "generate" -> chooseDocs=true; "images" -> imageInput.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)); "merge" -> mergeInput.launch(arrayOf("application/pdf")); else -> pdfInput.launch(arrayOf("application/pdf")) } },enabled=!busy,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha=.94f),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(18.dp),verticalAlignment=Alignment.CenterVertically) { Icon(when(key) { "edit" -> Icons.Outlined.Edit; "images" -> Icons.Outlined.PhotoLibrary; "raster" -> Icons.Outlined.Image; "generate" -> Icons.Outlined.Description; else -> Icons.Outlined.PictureAsPdf },null,tint=MaterialTheme.colorScheme.primary); Text(label,Modifier.weight(1f).padding(start=16.dp),style=MaterialTheme.typography.titleMedium); Icon(Icons.Outlined.ChevronRight,null) }
                    }
                }
                if(pending.isNotEmpty()) item { Text("Unfinished operations",style=MaterialTheme.typography.titleMedium) }
                items(pending,key={ it.first }) { (id,label) -> OutlinedButton(onClick={ model.run { val j=utility.session(id); kind=j.getString("kind"); title=j.optString("title",pdfFileStem(j.getJSONArray("names").getString(0))); ranges=j.optString("selection"); quality=PdfQuality.valueOf(j.optString("quality",PdfQuality.ORIGINAL.name)); sessionId=id; revision++ } },modifier=Modifier.fillMaxWidth()) { Text("Resume ${label} operation") } }
                item { TextButton(onClick={ licenses=true }) { Text("PDF licenses and attribution") } }
            } else {
                item { Text(when(kind) { "split" -> "Split PDF"; "merge" -> "Merge PDF"; "raster" -> "PDF to Image"; "images" -> "Image to PDF"; "edit" -> "Export edited PDF"; else -> "Generate PDF" },style=MaterialTheme.typography.headlineSmall) }
                if(session==null || session.optString("state")=="complete") item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                else if(working) item { val done=active?.progress?.getInt("done",0) ?: 0; val total=active?.progress?.getInt("total",0) ?: 0; Text("Exporting to device storage…"); if(total>0) { LinearProgressIndicator(progress={ done.toFloat()/total },modifier=Modifier.fillMaxWidth()); Text("$done of $total") } else LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick={ active?.let { utility.work.cancelWorkById(it.id) } }) { Text("Cancel export") } }
                else {
                    if(active?.state==WorkInfo.State.FAILED || active?.state==WorkInfo.State.CANCELLED) item { Text(active.outputData.getString("error") ?: "Export interrupted. Finished files remain at your destination; retry continues remaining work.",color=MaterialTheme.colorScheme.error); Button(onClick={ model.run { val j=utility.session(sessionId); utility.enqueue(sessionId,Uri.parse(j.getString("destination")),j.getBoolean("tree"),j.getString("title"),j.optString("selection")); choosingExport=false; revision++ } }) { Text("Retry export") } }
                    item { OutlinedTextField(title,{ title=it },label={ Text(if(kind in listOf("split","raster")) "Output folder uses original PDF name" else "Output filename") },enabled=kind !in listOf("split","raster"),singleLine=true,modifier=Modifier.fillMaxWidth()) }
                    if(kind in listOf("split","raster")) item { OutlinedTextField(ranges,{ ranges=it },label={ Text("Pages or ranges") },placeholder={ Text("All pages, or 1,3-5") },modifier=Modifier.fillMaxWidth()); Text(if(kind=="split") "Each comma-separated range becomes a PDF. Blank splits every page. Files are grouped in a folder named after the source." else "PNG images at up to 3000 px, grouped in a folder named after the source.",style=MaterialTheme.typography.bodySmall) }
                    if(kind=="merge" && session!=null) item {
                        val order=session!!.getJSONArray("order").let { a -> List(a.length()) { a.getInt(it) } }
                        Text("Drag to arrange merge order",style=MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick={ addingMerge=true; pdfInput.launch(arrayOf("application/pdf")) },enabled=!busy) { Icon(Icons.Outlined.Add,null,Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Add PDF") }
                        if(order.size<2) Text("Add another PDF to merge.",style=MaterialTheme.typography.bodySmall)
                        ReorderList(order.map { it.toString() },{ key -> pdfFileStem(session!!.getJSONArray("names").getString(key.toInt()))+" - "+session!!.getJSONArray("counts").getInt(key.toInt())+" pages" },trailing={ key ->
                            IconButton(onClick={ utility.removeMergeInput(sessionId,key.toInt()); val j=utility.session(sessionId); title=j.getJSONArray("order").let { list -> (0 until list.length()).joinToString(" ") { pdfFileStem(j.getJSONArray("names").getString(list.getInt(it))) } }+" merged"; revision++ },enabled=!busy) { Icon(Icons.Outlined.Close,"Remove PDF ${key.toInt()+1}") }
                        }) { ordered -> val j=utility.session(sessionId).put("order",JSONArray(ordered.map(String::toInt))); utility.save(sessionId,j); title=ordered.joinToString(" ") { pdfFileStem(j.getJSONArray("names").getString(it.toInt())) }+" merged"; revision++ }
                    }
                    item { if(rememberedFolder!=null) { Text("Destination: $folderLabel",style=MaterialTheme.typography.bodyMedium); OutlinedButton(onClick={ changingFolder=true; folderDestination.launch(rememberedFolder) },enabled=!busy) { Text("Change destination folder") } } }
                    if(kind !in listOf("raster","edit")) item { Text("PDF quality",style=MaterialTheme.typography.labelLarge); Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { PdfQuality.entries.forEach { q -> FilterChip(quality==q,{ quality=q; utility.save(sessionId,utility.session(sessionId).put("quality",q.name)); revision++ },label={ Text(q.name.lowercase().replaceFirstChar(Char::uppercase)) }) } } }
                    item { Button(onClick={ if(kind in listOf("split","raster")) { val count=session!!.getJSONArray("counts").getInt(0); try { if(kind=="split") splitGroups(ranges,count) else pageSelection(ranges,count); chooseDestination() } catch(t:Exception) { model.error.value=t.message } } else chooseDestination() },enabled=title.isNotBlank() && !busy && (kind!="merge" || session!!.getJSONArray("order").length()>=2),modifier=Modifier.fillMaxWidth()) { Text(if(rememberedFolder==null) "Choose destination and export" else if(kind=="raster") "Export images" else if(kind=="split") "Export PDFs" else "Export PDF") }; if(kind=="edit") OutlinedButton(onClick={ choosingExport=false },modifier=Modifier.fillMaxWidth()) { Text("Return to PDF editor") }; OutlinedButton(onClick={ unsaved=true },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Discard operation") } }
                }
            }
        }
    }
    if(passwordDialog) AlertDialog(onDismissRequest={ passwordDialog=false; picked=emptyList(); password="" },title={ Text("Open selected PDF") },text={ Column { Text("The source stays untouched. Enter its owner password only if protected."); OutlinedTextField(password,{ password=it },label={ Text("Owner password, if needed") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true) } },confirmButton={ Button(onClick=::acceptInput,enabled=!busy) { Text("Open") } },dismissButton={ OutlinedButton(onClick={ passwordDialog=false; password=""; picked=emptyList() }) { Text("Cancel") } })
    if(chooseDocs) FolioPageChooser(model,false,{ chooseDocs=false }) { selected -> model.run {
        sessionId=utility.fromPages(selected); kind="generate"; title=selected.map { it.documentId }.distinct().mapNotNull { id -> library.documents.find { it.id==id }?.title }.joinToString(" "); chooseDocs=false; revision++
    } }
    if(unsaved) AlertDialog(
        onDismissRequest={ unsaved=false },
        title={ Text("Leave with unsaved changes?") },
        text={ Text("Export a new file to keep your work, or discard this temporary session. The source and Folio documents remain unchanged.") },
        confirmButton={
            Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),horizontalAlignment=Alignment.End) {
                Button(onClick={ unsaved=false; choosingExport=true }) { Text("Save and Export") }
                OutlinedButton(onClick={ model.run { val discardId=sessionId; pending=pending.filter { it.first!=discardId }; sessionId=""; utility.discard(discardId); choosingExport=false; unsaved=false; revision++ } }) { Text("Discard Changes") }
                TextButton(onClick={ unsaved=false }) { Text("Cancel") }
            }
        }
    )
    if(licenses) AlertDialog(onDismissRequest={ licenses=false },title={ Text("PDF licenses") },text={ Text("iText Community 9.8.0, AGPL-3.0; Copyright iText Group NV. Bouncy Castle, MIT. Corresponding source, license notices and build instructions accompany Folio's source bundle.") },confirmButton={ Button(onClick={ licenses=false }) { Text("Close") } })
}

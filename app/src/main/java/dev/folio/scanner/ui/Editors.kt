@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.scanner.ui

import androidx.compose.animation.togetherWith
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.scanner.data.*
import dev.folio.scanner.processing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DocumentEditor(doc: Document?, model: LibraryViewModel, back: () -> Unit, scan: () -> Unit, edit: (String) -> Unit, replace: (String) -> Unit, pdf: () -> Unit, resume: (String) -> Unit, matchPage: String = "", documentId:String=doc?.id.orEmpty()) {
    val observedPages by remember(documentId) { if(documentId.isNotEmpty()) model.repository.dao.observePages(documentId) else kotlinx.coroutines.flow.flowOf(emptyList()) }.collectAsStateWithLifecycle<List<Page>?>(null)
    val pages=observedPages.orEmpty()
    var selected by rememberSaveable(documentId) { mutableStateOf(listOf<String>()) }
    var pageAction by remember { mutableStateOf<Page?>(null) }
    var renaming by remember { mutableStateOf<Page?>(null) }
    var highlighted by rememberSaveable(matchPage) { mutableStateOf(matchPage) }
    val context=LocalContext.current
    val preferences=remember { context.getSharedPreferences("appearance",0) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("documentGalleryGrid",true)) }
    val gridState=rememberLazyGridState()
    val reorderBounds=remember { mutableStateMapOf<String,androidx.compose.ui.geometry.Rect>() }
    var draggedPage by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    LaunchedEffect(pages) { reorderBounds.keys.retainAll(pages.map { it.id }.toSet()) }
    var dragTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(dragTarget) { if(dragTarget!=null) { val index=pages.indexOfFirst { it.id==dragTarget }; val last=gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0; if(index>=last-1 && index<pages.lastIndex) gridState.animateScrollToItem(index) } }

    LaunchedEffect(matchPage,pages.isNotEmpty()) {
        val index=pages.indexOfFirst { it.id==matchPage }
        if(index>=0 && highlighted.isNotEmpty()) { gridState.scrollToItem(index); delay(5000); highlighted="" }
    }
    var deleting by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var printing by rememberSaveable { mutableStateOf(false) }
    var documentMenu by remember { mutableStateOf(false) }
    var textScreen by rememberSaveable { mutableStateOf(false) }; var textPage by rememberSaveable { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler(enabled=textScreen) { textScreen=false }
    if(textScreen && doc!=null) { ExtractedTextScreen(doc.id,textPage,model) { textScreen=false }; return }
    val busy by model.busy.collectAsStateWithLifecycle()
    val drafts by produceState(emptyList<ScanDraft>(), doc?.id, busy) { value = doc?.let { model.repository.drafts(it.id) }.orEmpty() }
    fun reorder(page: Page, delta: Int) {
        val index = pages.indexOfFirst { it.id == page.id }; val target = index + delta
        if (target in pages.indices) {
            val ids = pages.map { it.id }.toMutableList(); ids[index] = ids[target].also { ids[target] = ids[index] }
            model.run { model.repository.reorder(page.documentId, ids) }
        }
    }
    LaunchedEffect(observedPages) { if(observedPages!=null) selected=selected.filter { id -> pages.any { it.id==id } } }
    androidx.activity.compose.BackHandler(selected.isNotEmpty()) { selected=emptyList() }
    fun toggle(id:String) { selected=if(id in selected) selected-id else selected+id }
    val scopedPages=if(selected.isEmpty()) pages else pages.filter { it.id in selected }
    Scaffold(topBar = { TopAppBar(title = { Text(if(selected.isEmpty()) doc?.title ?: "Document" else "${selected.size} selected",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) },navigationIcon={
        IconButton(onClick={ if(selected.isNotEmpty()) selected=emptyList() else back() }) { if(selected.isNotEmpty()) Icon(Icons.Outlined.Close,"Cancel selection") else Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") }
    },actions={
        IconButton(onClick={ grid=!grid; preferences.edit { putBoolean("documentGalleryGrid",grid) } },enabled=draggedPage==null) { Icon(if(grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView,if(grid) "List view" else "Grid view") }
        if(doc!=null) IconButton(onClick={ sharing=true },enabled=scopedPages.isNotEmpty()) { Icon(Icons.Outlined.Share,if(selected.isEmpty()) "Share document" else "Share selected pages") }
        if(selected.isNotEmpty()) IconButton(onClick={ deleting=true }) { Icon(Icons.Outlined.Delete,"Delete selected pages") }
        if(doc!=null) Box {
            IconButton(onClick={ documentMenu=true }) { Icon(Icons.Outlined.MoreVert,"Document actions") }
            FolioOverflowMenu(documentMenu,{ documentMenu=false },if(selected.isEmpty()) doc.title else "${selected.size} selected") {
                if(selected.isEmpty()) {
                    FolioMenuItem(label="Export PDF",onClick={ documentMenu=false; sharing=true },enabled=pages.isNotEmpty())
                    MenuSeparator()
                    FolioMenuItem(label="Extract Text",onClick={ documentMenu=false; textPage=null; textScreen=true },enabled=pages.isNotEmpty())
                    MenuSeparator()
                    FolioMenuItem(label=if(doc.favorite) "Remove favorite" else "Favorite",onClick={ documentMenu=false; model.run { model.repository.favorite(doc.id) } })
                } else {
                    FolioMenuItem(label="Apply filter",onClick={ documentMenu=false; filtering=true })
                }
                MenuSeparator()
                FolioMenuItem(label="Select all pages",onClick={ documentMenu=false; selected=pages.map { it.id } },enabled=pages.isNotEmpty())
            }
        }
    }) },bottomBar={
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start=16.dp,end=16.dp,top=8.dp,bottom=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick={ printing=true },enabled=doc!=null && scopedPages.isNotEmpty(),modifier=Modifier.heightIn(min=56.dp).semantics { contentDescription="Print document" }) {
                Icon(Icons.Outlined.Print,null); Spacer(Modifier.width(8.dp)); Text("Print")
            }
            Spacer(Modifier.weight(1f))
            if(doc!=null && selected.isEmpty()) ExtendedFloatingActionButton(onClick=scan,icon={ Icon(Icons.Outlined.CameraAlt,null) },text={ Text("Add pages") },modifier=Modifier.semantics { contentDescription="Add pages" })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            drafts.forEach { draft -> TextButton(onClick = { resume(draft.id) }, modifier = Modifier.fillMaxWidth()) { Text("Continue unfinished scan") } }
            if (pages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Description, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(if (doc == null) "Document unavailable" else "A fresh start", style = MaterialTheme.typography.headlineMedium)
                    Text(if (doc == null) "Return to your library." else "Your document is saved. It has no pages yet.")
                }
            } else LazyVerticalGrid(if(grid) GridCells.Adaptive(156.dp) else GridCells.Fixed(1), Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom=80.dp), state=gridState, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(pages, key = { it.id }) { page ->
                    @Composable fun pageActions(modifier:Modifier=Modifier) {
                        Row(modifier,verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(page.id in selected,{ checked -> selected=if(checked) selected+page.id else selected-page.id },modifier=Modifier.semantics { contentDescription="Select ${if(page.pageName==null) "page ${page.position+1}" else page.label()}" })
                            Text(page.label(),Modifier.weight(1f),maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                            IconButton(onClick={ pageAction=page }) { Icon(Icons.Outlined.MoreVert,"Actions for ${if(page.pageName==null) "page ${page.position+1}" else page.label()}") }
                        }
                    }
                    Column(Modifier.animateItem().zIndex(if(draggedPage==page.id) 1f else 0f).graphicsLayer { scaleX=if(draggedPage==page.id) 1.035f else 1f; scaleY=scaleX; alpha=if(draggedPage==page.id) .92f else 1f; translationX=if(draggedPage==page.id) dragOffset.x else 0f; translationY=if(draggedPage==page.id) dragOffset.y else 0f }.onGloballyPositioned { reorderBounds[page.id]=it.boundsInRoot() }.clickable { highlighted=""; if(selected.isNotEmpty()) toggle(page.id) else edit(page.id) }.then(Modifier.pageDragGesture(page.id,pages.map { it.id },reorderBounds,{ draggedPage=it },{ dragTarget=it },{ dragOffset=it },{ toggle(page.id) },{ ids -> model.run { model.repository.reorder(documentId,ids) } })).then(if(grid) Modifier else Modifier.border(1.dp,if(page.id in selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,MaterialTheme.shapes.medium).background(if(page.id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,MaterialTheme.shapes.medium).padding(8.dp))) {
                    val accent by androidx.compose.animation.animateColorAsState(if(page.id in selected || page.id==highlighted || page.id==dragTarget) MaterialTheme.colorScheme.primary.copy(alpha=.6f) else MaterialTheme.colorScheme.outlineVariant,animationSpec=tween(140),label="Search match")
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Box((if(grid) Modifier.fillMaxWidth().aspectRatio(.78f) else Modifier.size(88.dp,116.dp)).border(if(page.id in selected || dragTarget==page.id) 2.dp else 1.dp,accent,androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(if(page.id in selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow).semantics { this.selected=page.id in selected }) {
                        PageThumbnail(page,model,"Preview ${if(page.pageName==null) "page ${page.position + 1}" else page.label()}",Modifier.fillMaxSize().padding(4.dp))
                        if(dragTarget==page.id) Surface(Modifier.align(Alignment.BottomStart).padding(6.dp),color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) { Text(if(draggedPage==page.id) "Moving" else "Drop here",Modifier.padding(6.dp),style=MaterialTheme.typography.labelSmall) }

                        if(page.id in selected) Icon(Icons.Outlined.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.TopEnd).padding(8.dp).background(MaterialTheme.colorScheme.primaryContainer,androidx.compose.foundation.shape.CircleShape))
                        if(page.id==highlighted) Surface(Modifier.align(Alignment.BottomEnd).padding(6.dp),color=MaterialTheme.colorScheme.primaryContainer.copy(alpha=.95f),shape=MaterialTheme.shapes.small) { Text("Match",Modifier.padding(horizontal=8.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall) }
                    }
                    if(!grid) pageActions(Modifier.weight(1f))
                    }
                    if(grid) pageActions()
                } }
            }
        }
    }
    if (sharing && doc != null) DocumentShareSheet(doc, scopedPages, model) { sharing = false }
    pageAction?.let { page -> FolioOverflowMenu(true,{pageAction=null},page.label()) {
        listOf("Edit", "Rename", "Extract Text", "Rotate", "Duplicate", "Move earlier", "Move later", "Replace / retake", "Delete").forEachIndexed { index, action ->
            if(index>0) MenuSeparator()
            FolioMenuItem(action,onClick = {
                pageAction = null
                when (action) {
                    "Edit" -> edit(page.id)
                    "Rename" -> renaming=page
                    "Extract Text" -> { textPage=page.id; textScreen=true }
                    "Rotate" -> model.run { model.repository.edit(page.id, rotation = (page.rotation + 90) % 360) }
                    "Duplicate" -> model.run { model.repository.duplicatePage(page.id) }
                    "Move earlier" -> reorder(page, -1)
                    "Move later" -> reorder(page, 1)
                    "Replace / retake" -> replace(page.id)
                    "Delete" -> { selected = listOf(page.id); deleting = true }
                }
            }, enabled = when(action) { "Move earlier" -> page.position > 0; "Move later" -> page.position < pages.lastIndex; else -> true })
        }
    } }
    if(printing && doc!=null) DocumentPrintSheet(doc,scopedPages,model,selectionOnly=selected.isNotEmpty()) { printing=false }
    renaming?.let { page -> NameDialog("Rename page","Page name",page.pageName.orEmpty(),busy,{ renaming=null },optional=true) { name -> model.run { model.repository.renamePage(page.id,name); renaming=null } } }
    if (deleting && doc != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("Delete ${selected.size} pages?") }, text = { Text("Selected pages will move to Recycle Bin. You can restore them to this document.") }, confirmButton = {
        TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick = { model.run { model.repository.deletePages(doc.id, selected.toSet()); selected = emptyList(); deleting = false } }) { Text("Move to Recycle Bin") }
    }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } })
    if (filtering) AlertDialog(onDismissRequest = { filtering = false }, title = { Text("Apply to ${selected.size} pages") }, text = { Column {
        Enhancement.presets.forEach { preset -> TextButton(onClick = { model.run { selected.forEach { model.repository.edit(it, enhancement = Enhancement(preset)) }; filtering = false; selected = emptyList() } }) { Text(preset) } }
    } }, confirmButton = { TextButton(onClick = { filtering = false }) { Text("Cancel") } })
}

@Composable
fun PageEditor(initialPageId: String, model: LibraryViewModel, back: () -> Unit, crop: (String) -> Unit, draft: Boolean = false, retake: (() -> Unit)? = null, accepted: ((String) -> Unit)? = null) {
    var pageId by rememberSaveable(initialPageId) { mutableStateOf(initialPageId) }
    var swipeDirection by remember { mutableIntStateOf(1) }
    var sharing by rememberSaveable { mutableStateOf(false) }
    var sizeSheet by rememberSaveable { mutableStateOf(false) }
    var size by rememberSaveable(pageId) { mutableStateOf("Original") }
    var fitting by rememberSaveable(pageId) { mutableStateOf("Fit") }
    var paperWidth by rememberSaveable(pageId) { mutableDoubleStateOf(0.0) }
    var paperHeight by rememberSaveable(pageId) { mutableDoubleStateOf(0.0) }
    var paperLoaded by rememberSaveable(pageId) { mutableStateOf(false) }
    val layout=PageLayout(size,fitting,paperWidth,paperHeight)
    var editorPage by remember(pageId) { mutableStateOf<Page?>(null) }
    var adjustments by rememberSaveable { mutableStateOf(false) }
    var before by rememberSaveable { mutableStateOf(false) }
    var source by remember(pageId) { mutableStateOf<Bitmap?>(null) }
    var preview by remember(pageId) { mutableStateOf<Bitmap?>(null) }
    var configValue by rememberSaveable(pageId) { mutableStateOf("") }
    var rotation by rememberSaveable(pageId) { mutableIntStateOf(-1) }
    val config = Enhancement.decode(configValue)
    var processing by remember(pageId) { mutableStateOf(false) }
    var failed by remember(pageId) { mutableStateOf(false) }
    var loadingSource by remember(pageId) { mutableStateOf(true) }
    var renderedInput by remember(pageId) { mutableStateOf<List<Any?>>(emptyList()) }
    val busy by model.busy.collectAsStateWithLifecycle()
    var reload by remember { mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { loadingSource=true; reload++ }
    LaunchedEffect(pageId, reload) {
        loadingSource=true; failed=false
        try {
            val scan = if (draft) model.repository.draft(pageId) else null
            val page = if (!draft) requireNotNull(model.repository.dao.page(pageId)) else null
            editorPage=page
            if(!paperLoaded) { val paper=page?.layout() ?: scan?.replacement?.let { model.repository.dao.page(it)?.layout() } ?: PageLayout(); size=paper.size; fitting=paper.fit; paperWidth=paper.widthMm; paperHeight=paper.heightMm; paperLoaded=true }
            if (configValue.isEmpty()) configValue = page?.enhancement ?: Enhancement().encode()
            if (rotation < 0) rotation = page?.rotation ?: 0
            source = withContext(Dispatchers.IO) {
                model.pipeline.correct(File(scan?.original ?: page!!.originalImageUri), decodeCorners(scan?.crop ?: page!!.crop), 3200)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { android.util.Log.e("Folio editor", "Could not load page preview", failure); failed = true; model.error.value = "Could not open page. Return to the document and retry." }
        finally { loadingSource=false }
    }
    val rendering=remember { Mutex() }
    val previewInput=listOf(source,configValue,rotation,layout)
    LaunchedEffect(source, configValue, rotation, layout) {
        val bitmap = source ?: return@LaunchedEffect
        processing = true
        try { delay(150); preview = withContext(Dispatchers.Default) { rendering.withLock {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val enhanced=model.pipeline.enhance(bitmap,config,rotation.coerceAtLeast(0))
            val result=try { model.pipeline.pageCanvas(enhanced,layout) } catch(failure:Throwable) { enhanced.recycle(); throw failure }
            if(result!==enhanced) enhanced.recycle()
            try { kotlinx.coroutines.currentCoroutineContext().ensureActive(); result } catch(cancel: CancellationException) { result.recycle(); throw cancel }
        } }; renderedInput=previewInput }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: OutOfMemoryError) { model.error.value = "Not enough memory for this preview. Choose a smaller image." }
        catch (_: Exception) { model.error.value = "Preview failed. Choose another filter or reopen the page." }
        finally { processing = false }
    }
    fun save() { model.run { if (draft) { val doc = model.repository.draft(pageId).documentId; model.repository.acceptDraft(pageId, config, rotation, layout); accepted?.invoke(doc) } else { model.repository.edit(pageId, enhancement = config, rotation = rotation, layout=layout); back() } } }
    val canSave = source != null && !busy && !loadingSource && renderedInput==previewInput
    var editorPrinting by rememberSaveable { mutableStateOf(false) }
    val printDocument by produceState<Document?>(null,editorPage?.documentId) { value=editorPage?.let { model.repository.dao.document(it.documentId) } }
    val printPages by remember(editorPage?.documentId) { editorPage?.let { model.repository.dao.observePages(it.documentId) } ?: kotlinx.coroutines.flow.flowOf(emptyList<Page>()) }.collectAsStateWithLifecycle(emptyList())
    val pageIndex=printPages.indexOfFirst { it.id==pageId }
    fun swipePage(direction:Int) {
        if(draft || !canSave || processing || adjustments || sizeSheet || sharing || editorPrinting) return
        val target=printPages.getOrNull(pageIndex+direction) ?: return
        model.run {
            val persisted=editorPage ?: return@run
            if(persisted.enhancement!=config.encode() || persisted.rotation!=rotation || persisted.layout()!=layout)
                model.repository.edit(pageId,enhancement=config,rotation=rotation,layout=layout)
            swipeDirection=direction
            pageId=target.id
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text(if(draft) "Edit scan" else "Edit page",maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(!draft && pageIndex>=0) Text("${pageIndex+1} of ${printPages.size}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } }, navigationIcon = { IconButton(onClick = back, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel editing") } }, actions = {
        if(!draft) IconButton(onClick={ model.run { model.repository.edit(pageId,enhancement=config,rotation=rotation,layout=layout); editorPage=model.repository.dao.page(pageId); sharing=true } },enabled=canSave) { Icon(Icons.Outlined.Share,"Share page") }
        TextButton(onClick = { configValue = Enhancement().encode(); rotation = 0 }) { Text("Reset") }
        if (!draft) FilledTonalButton(onClick = ::save, enabled = canSave) { Text("Save") }
    }) }, bottomBar = {
        if(!draft) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)) {
            FilledTonalButton(onClick={ editorPrinting=true },enabled=printDocument!=null && printPages.isNotEmpty(),modifier=Modifier.semantics { contentDescription="Print document" }) { Icon(Icons.Outlined.Print,null); Spacer(Modifier.width(8.dp)); Text("Print") }
        }
        if (draft) Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=4.dp)) { Text("Accept page") } }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val panelWidth=(maxWidth*.38f).coerceAtMost(360.dp)
            val panelHeight=(maxHeight*.42f).coerceAtMost(360.dp)
            @Composable fun canvas(canvasModifier: Modifier) {
            Box(canvasModifier.contentOutline().background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
                val visible = if(before) source else preview
                androidx.compose.animation.AnimatedContent(pageId,transitionSpec={
                    (androidx.compose.animation.slideInHorizontally { it*swipeDirection/4 } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutHorizontally { -it*swipeDirection/4 } + androidx.compose.animation.fadeOut())
                },label="Page navigation") { displayedId ->
                    if(displayedId==pageId) visible?.let { key(pageId,adjustments) { DocumentCanvas(it, if(before) "Original page preview" else "Enhanced page preview", Modifier.fillMaxSize(), if(before) rotation.coerceAtLeast(0) else 0, if(draft) null else ::swipePage) } }
                }
                if(visible!=null) FilterChip(before,{ before=!before },label={ Text(if(before) "Before" else "After") },colors=FilterChipDefaults.filterChipColors(containerColor=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.94f)),modifier=Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (processing || source == null && !failed) CircularProgressIndicator()
                if (failed) Text("Page unavailable")
            }
            }
            @Composable fun controls() {
            Column(Modifier.padding(horizontal=8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Enhancement.presets.forEach { preset -> FilterChip(config.preset == preset, { configValue = config.copy(preset = preset).encode() }, label = { Text(preset) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    retake?.let { TextButton(onClick = it, enabled = !busy) { Text("Retake") } }
                    TextButton(onClick = { crop(pageId) }) { Icon(Icons.Outlined.Crop, null); Text("Crop") }
                    TextButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.AutoMirrored.Outlined.RotateRight, null); Text("Rotate") }
                    TextButton(onClick = { sizeSheet=true }) { Icon(Icons.Outlined.AspectRatio,null); Text("Page size") }
                    TextButton(onClick = { adjustments=true }) { Icon(Icons.Outlined.Tune,null); Text("Adjustments") }
                    TextButton(onClick = { model.run { if (draft) { model.repository.cropDraft(pageId, Geometry.full); configValue = Enhancement().encode(); rotation = 0; reload++ } else { model.repository.edit(pageId, corners = Geometry.full, enhancement = Enhancement(), rotation = 0); back() } } }, enabled = source != null && !busy) { Text("Restore original") }
                }

            }
            }
            @Composable fun adjustmentPanel(panelModifier: Modifier) {
                Column(panelModifier.background(MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha=.96f)).border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.35f))) {
                    Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("Adjustments",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                        TextButton(onClick={ adjustments=false }) { Text("Done") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).semantics { contentDescription="Adjustment controls" },verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("Light",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Adjustment("Brightness",config.brightness.toFloat(),-80f..80f) { configValue=config.copy(brightness=it.toDouble()).encode() }
                        Adjustment("Contrast",config.contrast.toFloat(),.5f..2f) { configValue=config.copy(contrast=it.toDouble()).encode() }
                        Text("Color",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Adjustment("Saturation",config.saturation.toFloat(),0f..2f) { configValue=config.copy(saturation=it.toDouble()).encode() }
                        Text("Detail",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Adjustment("Sharpness",config.sharpness.toFloat(),0f..2f) { configValue=config.copy(sharpness=it.toDouble()).encode() }
                        if(config.preset in listOf("Black & White","Document")) Adjustment("Threshold",config.threshold.toFloat(),0f..30f) { configValue=config.copy(threshold=it.toDouble()).encode() }
                        Text("Document",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Adjustment("Shadow normalization",config.shadow.toFloat(),0f..1f) { configValue=config.copy(shadow=it.toDouble()).encode() }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
            if(maxWidth>maxHeight) Row(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxHeight())
                Box(Modifier.width(panelWidth).fillMaxHeight()) {
                    if(adjustments) adjustmentPanel(Modifier.fillMaxSize())
                    else Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),contentAlignment=Alignment.BottomCenter) { controls() }
                }
            } else Column(Modifier.fillMaxSize()) {
                canvas(Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.fillMaxWidth().animateContentSize(animationSpec=tween(180))) {
                    if(adjustments) adjustmentPanel(Modifier.fillMaxWidth().height(panelHeight))
                    else controls()
                }
            }
        }
    }

    if(sizeSheet) PageSizeSheet(layout,source,rotation.coerceAtLeast(0),{ sizeSheet=false }) { paper -> size=paper.size; fitting=paper.fit; paperWidth=paper.widthMm; paperHeight=paper.heightMm; sizeSheet=false }
    if(editorPrinting) printDocument?.let { document -> DocumentPrintSheet(document,printPages.filter { it.id==pageId },model,selectionOnly=true) { editorPrinting=false } }
    if(sharing && !draft) editorPage?.let { page ->
        val document by produceState<Document?>(null,page.documentId) { value=model.repository.dao.document(page.documentId) }
        document?.let { DocumentShareSheet(it,listOf(page),model,pageOnly=true) { sharing=false } }
    }
}

@Composable
private fun Adjustment(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
        Text("%.2f".format(java.util.Locale.US,value),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value, change, valueRange = range, modifier = Modifier.semantics { contentDescription = label })
}

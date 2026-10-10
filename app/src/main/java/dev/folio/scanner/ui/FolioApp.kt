@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.scanner.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.edit
import androidx.navigation.compose.*
import dev.folio.scanner.data.*
import java.text.DateFormat
import java.util.Date

@Composable
fun FolioApp(model: LibraryViewModel) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", 0) }
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val preparingPdf by model.preparingPdf.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    fun openDocument(id:String,match:String="") {
        if(state.documents.find {it.id==id}?.importedPdf==true) model.run {
            val session=model.utility.openManaged(id)
            val index=if(match.isEmpty()) 0 else model.repository.dao.page(match)?.position ?: 0
            nav.navigate("pdf-import/$session?page=$index")
        } else nav.navigate(if(match.isEmpty()) "document/$id" else "document/$id?match=$match")
    }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); model.error.value = null } }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val cameraRoute = route.startsWith("scanner/")
    FolioTheme(theme, cameraOverlay = cameraRoute) {
        // Each destination owns its system insets; nested Scaffolds must not add them twice.
        Scaffold(snackbarHost = { SnackbarHost(snack,Modifier.navigationBarsPadding()) }, contentWindowInsets = WindowInsets(0,0,0,0)) { outer ->
            Box(Modifier.fillMaxSize().padding(outer)) {
                NavHost(nav, "library", enterTransition={ androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) },
                    exitTransition={ androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)) },
                    popEnterTransition={ androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) },
                    popExitTransition={ androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)) }) {
                    composable("library",exitTransition={ if(targetState.destination.route=="pdf-workspace") androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { it } else androidx.compose.animation.fadeOut() },popEnterTransition={ if(initialState.destination.route=="pdf-workspace") androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it } else androidx.compose.animation.fadeIn() }) {
                        LibraryScreen(state, busy, model, open = { openDocument(it) }, settings = { nav.navigate("settings") }, recycle = { nav.navigate("recycle") }, openPage={ doc,page -> openDocument(doc,page) },text={ nav.navigate("text/$it") }, workspace={ nav.navigate("pdf-workspace") }, importedPdf={ nav.navigate("pdf-import/$it") },review={ doc,draft -> nav.navigate("document/$doc"); nav.navigate("scan-edit/$draft") })
                    }
                    composable("recycle") { RecycleBinScreen(model) { nav.popBackStack() } }
                    composable("document/{id}?match={match}", arguments=listOf(androidx.navigation.navArgument("match") { defaultValue="" })) { entry ->
                        val id = requireNotNull(entry.arguments?.getString("id"))
                        val doc = state.documents.find { it.id == id }
                        DocumentEditor(doc, model, { nav.popBackStack() }, scan = { nav.navigate("scanner/$id") }, edit = { nav.navigate("edit/$it") }, replace = { nav.navigate("scanner/$id/$it") }, pdf = { nav.navigate("pdf-workspace") }, resume = { nav.navigate("scan-edit/$it") }, matchPage=entry.arguments?.getString("match").orEmpty(),documentId=id)
                    }
                    composable("scanner/{id}") { entry ->
                        val id = requireNotNull(entry.arguments?.getString("id"))
                        ScannerScreen(id, model, { nav.popBackStack() }, { draft -> nav.navigate("scan-edit/$draft") })
                    }
                    composable("crop/{page}") { entry ->
                        CropScreen(requireNotNull(entry.arguments?.getString("page")), model, { nav.popBackStack() }, { nav.popBackStack() })
                    }
                    composable("edit/{page}") { entry ->
                        PageEditor(requireNotNull(entry.arguments?.getString("page")), model, { nav.popBackStack() }, { nav.navigate("crop/$it") })
                    }
                    composable("scan-edit/{draft}") { entry ->
                        ScanEditor(requireNotNull(entry.arguments?.getString("draft")), model,
                            finished = { doc -> nav.popBackStack("document/{id}?match={match}", false) },
                            retake = { doc, replacement -> nav.popBackStack("document/{id}?match={match}", false); nav.navigate(if (replacement == null) "scanner/$doc" else "scanner/$doc/$replacement") }, exit = { nav.popBackStack() })
                    }
                    composable("scanner/{id}/{replace}") { entry ->
                        ScannerScreen(requireNotNull(entry.arguments?.getString("id")), model, { nav.popBackStack() }, { draft -> nav.navigate("scan-edit/$draft") }, entry.arguments?.getString("replace"))
                    }
                    composable("settings") {
                        SettingsScreen(theme, { theme = it; preferences.edit { putString("theme", it) } }, { nav.popBackStack() }, { nav.navigate("drive-backup") }, { nav.navigate("ocr-settings") })
                    }
                    composable("drive-backup") { BackupScreen(model.backups) { nav.popBackStack() } }
                    composable("text/{id}") { entry -> ExtractedTextScreen(requireNotNull(entry.arguments?.getString("id")),null,model) { nav.popBackStack() } }
                    composable("ocr-settings") { OcrSettingsScreen(model) { nav.popBackStack() } }
                    composable("pdf-workspace",enterTransition={ androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { -it } },popExitTransition={ androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220)) { -it } }) {
                        PdfWorkspace(null, model, { nav.popBackStack() }, { nav.navigate("pdf/$it") })
                    }
                    composable("pdf-import/{session}?page={page}",arguments=listOf(androidx.navigation.navArgument("page") {type=androidx.navigation.NavType.IntType;defaultValue=0})) { entry ->
                        PdfWorkspace(null,model,{ nav.popBackStack() },{},initialSession=entry.arguments!!.getString("session").orEmpty(),importIntoFolio=true,initialPage=entry.arguments!!.getInt("page"))
                    }

                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                if (preparingPdf) AlertDialog(onDismissRequest = model::cancelPdfPreparation, title = { Text("Preparing PDF inputs") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Saving safe copies of selected pages before background processing."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }, confirmButton = { TextButton(onClick = model::cancelPdfPreparation) { Text("Cancel PDF preparation") } })
            }
        }
    }
}

@Composable
private fun LibraryScreen(state: LibraryState, busy: Boolean, model: LibraryViewModel, open: (String) -> Unit, settings: () -> Unit, recycle: () -> Unit,openPage:(String,String)->Unit,text:(String)->Unit, workspace:()->Unit,importedPdf:(String)->Unit,review:(String,String)->Unit) {
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var importUris by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var importPdf by rememberSaveable { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    val importer=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) {
            val pdfs=uris.filter { uri -> context.contentResolver.getType(uri)=="application/pdf" || model.utility.name(uri).endsWith(".pdf",true) }
            if(pdfs.isNotEmpty() && uris.size!=1) model.error.value="Choose one PDF, or several images."
            else { importUris=uris.map { it.toString() }; importPdf=pdfs.isNotEmpty() }
        }
    }
    val prefs = remember { context.getSharedPreferences("appearance",0) }
    var grid by rememberSaveable { mutableStateOf(prefs.getBoolean("libraryGrid",true)) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun leaveSearch() { query=""; searching=false; focus.clearFocus() }
    androidx.activity.compose.BackHandler(searching || query.isNotEmpty()) { leaveSearch() }
    val clearSearch by rememberUpdatedState { if(selection.isNotEmpty()) selection=emptyList() else leaveSearch() }
    val activeSearch=searching || query.isNotEmpty()
    // Only the resumed library search receives overlay priority, ahead of the IME.
    androidx.lifecycle.compose.LifecycleResumeEffect(activeSearch) {
        val dispatcher=if(android.os.Build.VERSION.SDK_INT>=33 && activeSearch) (context as? android.app.Activity)?.onBackInvokedDispatcher else null
        val callback=if(android.os.Build.VERSION.SDK_INT>=33) android.window.OnBackInvokedCallback { clearSearch() } else null
        if(android.os.Build.VERSION.SDK_INT>=33 && callback!=null) dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
        onPauseOrDispose { if(android.os.Build.VERSION.SDK_INT>=33 && callback!=null) dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
    var sort by rememberSaveable { mutableStateOf("Modified") }
    var sortMenu by remember { mutableStateOf(false) }
    var create by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var folderAction by remember { mutableStateOf<Folder?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var selected by remember { mutableStateOf<Document?>(null) }
    var selectionShare by rememberSaveable { mutableStateOf(false) }
    var selectionDelete by rememberSaveable { mutableStateOf(false) }
    fun toggle(id:String) { selection=if(id in selection) selection-id else selection+id }
    fun click(doc:Document) { if(selection.isNotEmpty()) toggle(doc.id) else open(doc.id) }
    LaunchedEffect(state.documents,state.loading) { if(!state.loading) selection=selection.filter { id -> state.documents.any { it.id==id } } }
    androidx.activity.compose.BackHandler(selection.isNotEmpty()) { selection=emptyList() }

    var action by remember { mutableStateOf("") }
    var share by rememberSaveable { mutableStateOf<String?>(null) }
    var print by rememberSaveable { mutableStateOf<String?>(null) }
    val expression=dev.folio.scanner.ocr.searchExpression(query)
    val recognizing by remember { model.repository.dao.observePendingOcrCount() }.collectAsStateWithLifecycle(0)
    val hits by remember(expression,query) { if(expression.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else model.repository.dao.searchOcr(expression,query.trim()) }.collectAsStateWithLifecycle(emptyList())
    val visibleHits=hits.filter { hit -> state.documents.any { it.id==hit.documentId && (tab!=1 || it.favorite) && (folder==null || it.folderId==folder) } }
    val docs = state.documents.filter { doc ->
        doc.title.contains(query, ignoreCase = true) && (tab != 1 || doc.favorite) && (folder == null || doc.folderId == folder)
    }.let { if (tab == 3) it.sortedByDescending { row -> row.modifiedAt }.take(20) else it }.let { rows -> when (sort) {
        "Name" -> rows.sortedBy { it.title.lowercase() }
        "Created" -> rows.sortedByDescending { it.createdAt }
        else -> rows.sortedByDescending { it.modifiedAt }
    } }
    val keyboardOpen=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    Scaffold(modifier=Modifier.imePadding(),topBar = {
        if(selection.isNotEmpty()) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={ selection=emptyList() }) { Icon(Icons.Outlined.Close,"Cancel document selection") }
            Text("${selection.size} selected",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            IconButton(onClick={ selectionShare=true }) { Icon(Icons.Outlined.Share,"Share selected documents") }
            IconButton(onClick={ selectionDelete=true }) { Icon(Icons.Outlined.Delete,"Delete selected documents") }
            IconButton(onClick={ selection=docs.map { it.id } }) { Icon(Icons.Outlined.SelectAll,"Select all documents") }
        } else if(keyboardOpen) Spacer(Modifier.statusBarsPadding().height(4.dp)) else
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(dev.folio.scanner.R.drawable.ic_folio_mark), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text("Folio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = recycle) { Icon(Icons.Outlined.DeleteOutline, "Recycle Bin") }
            IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "Settings") }
        }
    }, bottomBar = {
        if(!keyboardOpen && selection.isEmpty()) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp,vertical=8.dp)) {
        OutlinedButton(onClick={ importer.launch(arrayOf("image/*","application/pdf")) },enabled=!busy,modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileOpen,null); Spacer(Modifier.width(8.dp)); Text("Import") }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick=workspace,shape=MaterialTheme.shapes.large,modifier=Modifier.weight(1f).heightIn(min=56.dp)) { Icon(Icons.Outlined.PictureAsPdf,null); Spacer(Modifier.width(8.dp)); Text("PDF workspace",maxLines=2) }
            ExtendedFloatingActionButton(onClick={ create=true },icon={ Icon(Icons.Outlined.CameraAlt,null) },text={ Text("New document") },modifier=Modifier.weight(1f).semantics { contentDescription="New document" })
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).workspaceSwipe(!busy && selection.isEmpty() && !activeSearch && !create && importUris.isEmpty() && selected==null && !newFolder,true,workspace).padding(horizontal = 16.dp).onPreInterceptKeyBeforeSoftKeyboard { event ->
            if(activeSearch && event.key==Key.Back) { if(event.type==KeyEventType.KeyUp) leaveSearch(); true } else false
        }) {
            if(!keyboardOpen && query.isNotBlank() && recognizing>0) Text("$recognizing pages still recognizing text…",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search documents") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { Row {
                    if(keyboardOpen) IconButton(onClick={ grid=!grid; prefs.edit { putBoolean("libraryGrid",grid) } }) { Icon(if(grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView,if(grid) "List view" else "Grid view") }
                    if(searching || query.isNotEmpty()) IconButton(onClick=::leaveSearch) { Icon(Icons.Outlined.Close,"Close search") }
                } },
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={ focus.clearFocus() }),
                singleLine = true, modifier = Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) searching=true }, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(if(keyboardOpen) 4.dp else 16.dp))
            if(!keyboardOpen) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All documents", "Favorites", "Folders", "Recent").forEachIndexed { index, label ->
                    FilterChip(selected = tab == index, onClick = { tab = index; folder = null }, label = { Text(label) })
                }
            }
            if (tab == 2) {
                if(!keyboardOpen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { newFolder = true }) { Text("New folder") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp)) {
                    items(state.folders.filter { it.name.contains(query, true) }, key = { it.id }) { item ->
                        ListItem(headlineContent = { Text(item.name) }, leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            supportingContent = { Text("${state.documents.count { it.folderId == item.id }} documents") },
                            trailingContent = { IconButton(onClick = { folderAction = item }) { Icon(Icons.Outlined.MoreVert, "Folder actions for ${item.name}") } },
                            modifier = Modifier.clickable { folder = item.id; tab = 0 })
                    }
                    if (state.folders.isEmpty()) item { EmptyState(Icons.Outlined.Folder, "A place for everything", "Create folders for receipts, study notes, or work.") }
                }
            } else {
                if(!keyboardOpen) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.folders.find { it.id == folder }?.name ?: if (tab == 1) "Favorites" else "Documents", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (folder != null) TextButton(onClick = { folder = null }) { Text("Show all") }
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Text(sort); Icon(Icons.Outlined.ExpandMore, null) }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            listOf("Modified", "Created", "Name").forEachIndexed { index, label -> if(index>0) MenuSeparator(); DropdownMenuItem(text = { Text(label) }, onClick = { sort = label; sortMenu = false }) }
                        }
                    }
                    IconButton(onClick = { grid = !grid; prefs.edit { putBoolean("libraryGrid",grid) } }) { Icon(if (grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView, if (grid) "List view" else "Grid view") }
                }
                when {
                    query.isNotBlank() -> if(grid) LazyVerticalGrid(GridCells.Adaptive(156.dp),Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=100.dp)) {
                        items(docs,key={ "name-${it.id}" }) { d -> DocumentTile(d,model,{ click(d) },{ if(selection.isEmpty()) selected=d else toggle(d.id) },d.id in selection,{ toggle(d.id); focus.clearFocus() }) }
                        items(visibleHits,key={ "ocr-${it.pageId}" }) { hit -> SearchPageResult(hit,model,true) { openPage(hit.documentId,hit.pageId) } }
                        if(docs.isEmpty() && visibleHits.isEmpty()) item(span={ GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.Search,"No matches","Try another name or word. Pages become searchable as recognition finishes.") }
                    } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        items(docs,key={ "name-${it.id}" }) { d -> ListItem(colors=ListItemDefaults.colors(containerColor=if(d.id in selection) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),headlineContent={ Text(d.title) },supportingContent={ Text("${d.pageCount} pages · Document name") },leadingContent={ DocumentCover(d,model,Modifier.size(56.dp,72.dp)) },trailingContent={ IconButton(onClick={ if(selection.isEmpty()) selected=d else toggle(d.id) }) { Icon(if(d.id in selection) Icons.Outlined.CheckCircle else Icons.Outlined.MoreVert,"Actions for ${d.title}") } },modifier=Modifier.selectionAppearance(d.id in selection).combinedClickable(onClick={ click(d) },onLongClick={ toggle(d.id); focus.clearFocus() })) }
                        items(visibleHits,key={ "ocr-${it.pageId}" }) { hit -> SearchPageResult(hit,model,false) { openPage(hit.documentId,hit.pageId) } }
                        if(docs.isEmpty() && visibleHits.isEmpty()) item { EmptyState(Icons.Outlined.Search,"No matches","Try another name or word. Pages become searchable as recognition finishes.") }
                    }
                    state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    docs.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        EmptyState(Icons.Outlined.Description, if (query.isNotEmpty()) "No matches" else if (tab == 1) "Keep the important ones close" else "Start with a document",
                            if (query.isNotEmpty()) "Try another document name." else if (tab == 1) "Mark a document as a favorite to find it here." else "Create a document to start your library.")
                    }
                    grid -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 100.dp)) {
                        items(docs, key = { it.id }) { doc -> DocumentTile(doc,model,{ click(doc) },{ if(selection.isEmpty()) selected=doc else toggle(doc.id) },doc.id in selection,{ toggle(doc.id); focus.clearFocus() }) }
                    }
                    else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(docs, key = { it.id }) { doc ->
                            ListItem(colors=ListItemDefaults.colors(containerColor=if(doc.id in selection) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),headlineContent = { Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("${doc.pageCount} pages · ${date(doc.modifiedAt)}") }, leadingContent = { DocumentCover(doc,model,Modifier.size(56.dp,72.dp)) },
                                trailingContent = { IconButton(onClick = { if(selection.isEmpty()) selected=doc else toggle(doc.id) }) { Icon(if(doc.id in selection) Icons.Outlined.CheckCircle else Icons.Outlined.MoreVert, "Actions for ${doc.title}") } }, modifier=Modifier.selectionAppearance(doc.id in selection).combinedClickable(onClick={ click(doc) },onLongClick={ toggle(doc.id); focus.clearFocus() }))
                        }
                    }
                }
            }
        }
    }
    val chosen=selection.mapNotNull { id -> state.documents.find { it.id==id } }
    if(selectionShare && chosen.isNotEmpty()) MultiDocumentShareSheet(chosen,model) { selectionShare=false }
    if(selectionDelete) AlertDialog(onDismissRequest={ selectionDelete=false },title={ Text("Move ${chosen.size} documents to Recycle Bin?") },text={ Text("Their pages stay available for restoration. Cloud backups remain until permanent deletion.") },confirmButton={ TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={ model.run { chosen.forEach { model.repository.delete(it.id) }; selectionDelete=false; selection=emptyList() } }) { Text("Move to Recycle Bin") } },dismissButton={ TextButton(onClick={ selectionDelete=false }) { Text("Cancel") } })
    if (create) NameDialog("New document", "Document name", "", busy, { create = false },validate={ documentNameConflict(it,state.documents) }) { name -> model.run { val id = model.repository.create(name, folder); create = false; open(id) } }
    if(importUris.isNotEmpty() && importPdf) AlertDialog(onDismissRequest={ importUris=emptyList(); importPassword="" },title={ Text("Review PDF before import") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Review your PDF, then choose Save to Folio. Leaving an unsaved import asks for confirmation. Your source file stays intact."); OutlinedTextField(importPassword,{ importPassword=it },label={ Text("Owner password, if needed") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true) } },confirmButton={ Button(enabled=!busy,onClick={ val uri=android.net.Uri.parse(importUris.single()); val secret=importPassword; model.run { val id=model.utility.open("edit",listOf(uri),secret,initialImport=true); importUris=emptyList(); importPassword=""; importedPdf(id) } }) { Text("Review PDF") } },dismissButton={ TextButton(onClick={ importUris=emptyList(); importPassword="" }) { Text("Cancel") } })
    if(importUris.isNotEmpty() && !importPdf) NameDialog("Import images","Document name","",busy,{ importUris=emptyList() },validate={ documentNameConflict(it,state.documents) }) { name -> model.run {
        val id=model.repository.create(name,folder); var first:String?=null
        importUris.forEach { raw -> val uri=android.net.Uri.parse(raw); model.utility.grant(uri); context.contentResolver.openInputStream(uri)!!.use { val draft=model.repository.stageScan(id,it); if(first==null) first=draft } }
        importUris=emptyList(); first?.let { review(id,it) }
    } }
    if (newFolder) NameDialog("New folder", "Folder name", "", busy, { newFolder = false }) { name -> model.run { model.repository.createFolder(name); newFolder = false } }
    renameFolder?.let { item -> NameDialog("Rename folder", "Folder name", item.name, busy, { renameFolder = null }) { name -> model.run { model.repository.renameFolder(item, name); renameFolder = null } } }
    folderAction?.let { item ->
        FolioOverflowMenu(true,{folderAction=null},item.name) {
            FolioMenuItem("Rename",{renameFolder=item;folderAction=null})
            MenuSeparator()
            Text("Deleting this folder keeps its documents in your library.",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            FolioMenuItem("Delete folder",{model.run {model.repository.deleteFolder(item.id);folderAction=null}})
        }
    }
    selected?.let { doc ->
        FolioOverflowMenu(action.isEmpty(),{selected=null},doc.title) {
            listOf("Open", "Extract Text", "Rename", "Print", "Share", "Duplicate", if (doc.favorite) "Remove favorite" else "Favorite", "Move to folder", "Delete").forEachIndexed { index, label ->
                if(index>0) MenuSeparator()
                FolioMenuItem(label,enabled=label!="Print" || doc.pageCount>0, onClick = {
                    when (label) {
                        "Open" -> { selected=null; open(doc.id) }
                        "Extract Text" -> { selected=null; text(doc.id) }
                        "Share" -> { share=doc.id; selected=null }
                        "Print" -> { print=doc.id; selected=null }
                        "Duplicate" -> action = label
                        "Favorite", "Remove favorite" -> model.run { model.repository.favorite(doc.id); selected = null }
                        else -> action = label
                    }
                })
            }
        }
        if (action == "Rename") NameDialog("Rename document", "Document name", doc.title, busy, { action = ""; selected=null },validate={ documentNameConflict(it,state.documents,doc.id) }) { name -> model.run { model.repository.rename(doc.id, name); selected = null; action = "" } }
        if (action == "Duplicate") NameDialog("Duplicate document", "Document name", doc.title.take(113)+" (copy)", busy, { action = ""; selected=null },validate={ documentNameConflict(it,state.documents) }) { name -> model.run { model.repository.duplicate(doc.id,name); selected=null; action="" } }
        if (action == "Delete") AlertDialog(onDismissRequest = { action = "" }, title = { Text("Move ${doc.title} to Recycle Bin?") }, text = { Text("Your pages and originals will be kept. You can restore this document from Recycle Bin.") },
            confirmButton = { TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick = { model.run { model.repository.delete(doc.id); selected = null; action = "" } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { action = ""; selected=null }) { Text("Cancel") } })
        if (action == "Move to folder") AlertDialog(onDismissRequest = { action = "" }, title = { Text("Move to folder") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(onClick = { model.run { model.repository.move(doc.id, null); selected = null; action = "" } }) { Text("No folder") }
                state.folders.forEach { item -> TextButton(onClick = { model.run { model.repository.move(doc.id, item.id); selected = null; action = "" } }) { Text(item.name) } }
            }
        }, confirmButton = { TextButton(onClick = { action = "" }) { Text("Cancel") } })
    }
    share?.let { id ->
        val doc=state.documents.find { it.id==id }
        val pages by remember(id) { model.repository.dao.observePages(id) }.collectAsStateWithLifecycle(emptyList())
        if(doc!=null) DocumentShareSheet(doc,pages,model) { share=null }
    }
    print?.let { id ->
        val doc=state.documents.find { it.id==id }
        val pages by remember(id) { model.repository.dao.observePages(id) }.collectAsStateWithLifecycle(emptyList())
        if(doc!=null) DocumentPrintSheet(doc,pages,model) { print=null }
    }
}

@Composable
private fun Modifier.selectionAppearance(chosen:Boolean)=this.semantics { selected=chosen }
    .background(if(chosen) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(8.dp))
    .border(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(8.dp))

@Composable
private fun DocumentTile(doc: Document, model: LibraryViewModel, open: () -> Unit, actions: () -> Unit, chosen:Boolean=false, hold:()->Unit={}) {
    val pages by remember(doc.id) { model.repository.dao.observePages(doc.id) }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.selectionAppearance(chosen).combinedClickable(onClick=open,onLongClick=hold).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.78f).contentOutline().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            if (pages.isNotEmpty()) PageThumbnail(pages.first(),model,"Document preview",Modifier.fillMaxSize().padding(4.dp))
            else Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(40.dp))
            if (doc.favorite) Icon(Icons.Outlined.Star, "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(12.dp))
            if(doc.importedPdf) PdfBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
            if(chosen) Icon(Icons.Outlined.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.TopStart).padding(8.dp))
            FilledTonalIconButton(onClick = actions, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) { Icon(Icons.Outlined.MoreVert, "Actions for ${doc.title}") }
        }
        Spacer(Modifier.height(10.dp))
        Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text("${doc.pageCount} pages · ${date(doc.modifiedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DocumentCover(doc: Document,model: LibraryViewModel,modifier: Modifier) {
    val pages by remember(doc.id) { model.repository.dao.observePages(doc.id) }.collectAsStateWithLifecycle(emptyList())
    Box(modifier,contentAlignment=Alignment.Center) {
        if(pages.isNotEmpty()) PageThumbnail(pages.first(),model,"Document preview",Modifier.fillMaxSize())
        else Icon(Icons.Outlined.Description,null,tint=MaterialTheme.colorScheme.outline)
        if(doc.importedPdf) PdfBadge(Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

@Composable
private fun SettingsScreen(theme: String, change: (String) -> Unit, back: () -> Unit, backup: () -> Unit,ocr:()->Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) { Icon(painterResource(dev.folio.scanner.R.drawable.ic_folio_mark),null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary); Text("Folio",style=MaterialTheme.typography.titleLarge) }
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            listOf("System", "Light", "Dark", "AMOLED").forEach { mode ->
                Row(Modifier.fillMaxWidth().clickable { change(mode) }.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(theme == mode, { change(mode) }); Text(mode) }
            }
            HorizontalDivider()
            ListItem(headlineContent={ Text("Google Drive Backup") },supportingContent={ Text("Optional · Your documents stay local") },leadingContent={ Icon(Icons.Outlined.CloudUpload,null) },modifier=Modifier.clickable(onClick=backup))
            ListItem(headlineContent={ Text("Text recognition") },supportingContent={ Text("Offline · English printed documents") },leadingContent={ Icon(Icons.AutoMirrored.Outlined.TextSnippet,null) },modifier=Modifier.clickable(onClick=ocr))
            Text("Private by default", style = MaterialTheme.typography.titleMedium)
            Text("Folio stores documents locally by default. Optional Google Drive backup uploads your Folio backup data only to the account you authorize. Uninstalling Folio removes its local documents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, title: String, message: String) {
    Column(Modifier.padding(vertical = 36.dp, horizontal = 16.dp).widthIn(max = 360.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun NameDialog(title: String, label: String, initial: String, busy: Boolean, dismiss: () -> Unit, optional: Boolean = false, validate: (String)->String? = { null }, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val problem = when { name.trim().length>120 -> "Use 120 characters or fewer."; name.isNotEmpty() && name.isBlank() && !optional -> "Enter a name."; else -> validate(name.trim()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it }, label = { Text(label) }, singleLine = true,isError=problem!=null,supportingText={ problem?.let { Text(it,Modifier.semantics { liveRegion=androidx.compose.ui.semantics.LiveRegionMode.Polite }) } })
    }, confirmButton = { TextButton(onClick = { save(name.trim()) }, enabled = (optional || name.isNotBlank()) && problem==null && !busy) { Text("Save") } },
        dismissButton = { Row { if(optional) TextButton(onClick={ name="" }) { Text("Reset name") }; TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } } })
}

private fun date(time: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))

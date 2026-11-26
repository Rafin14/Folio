@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.scanner.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.edit
import androidx.navigation.compose.*
import androidx.navigation.NavBackStackEntry
import dev.folio.scanner.data.*
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TabMotionDuration = 280

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    setOf(initialState.destination.route, targetState.destination.route) == setOf("library", "pdf-workspace")

@Composable
private fun AnimatedVisibilityScope.tabMotion(direction: Int, sliding: Boolean): Modifier {
    val offset = transition.animateFloat(
        transitionSpec = { tween(if (sliding) TabMotionDuration else 0, easing = FastOutSlowInEasing) },
        label = "Tab slide"
    ) { state -> if (state == EnterExitState.Visible) 0f else direction * .24f }
    return Modifier.graphicsLayer { translationX = if (sliding) size.width * offset.value else 0f }
}

@Composable
fun FolioApp(model: LibraryViewModel) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", 0) }
    var theme by rememberSaveable { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
    var blurEnabled by rememberSaveable { mutableStateOf(preferences.getBoolean("glassBlurEnabled", true)) }
    var blurStrength by rememberSaveable { mutableIntStateOf(preferences.getInt("glassBlurStrength", 100).coerceIn(25, 150)) }
    var translucency by rememberSaveable { mutableIntStateOf(preferences.getInt("glassTranslucency", 50).coerceIn(0, 100)) }
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val preparingPdf by model.preparingPdf.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var newDocumentFolder by rememberSaveable { mutableStateOf<String?>(null) }
    fun beginDocument(folder: String?) {
        newDocumentFolder = folder
        creating = true
    }
    fun openDocument(id:String,match:String="") {
        if(state.documents.find {it.id==id}?.importedPdf==true) model.run {
            val session=model.utility.openManaged(id)
            val index=if(match.isEmpty()) 0 else model.repository.dao.page(match)?.position ?: 0
            nav.navigate("pdf-import/$session?page=$index&match=${match.isNotEmpty()}")
        } else nav.navigate(if(match.isEmpty()) "document/$id" else "document/$id?match=$match")
    }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); model.error.value = null } }
    val entry by nav.currentBackStackEntryAsState()
    val visibleEntries by nav.visibleEntries.collectAsStateWithLifecycle()
    val slidingTabs = visibleEntries.size == 2 &&
        visibleEntries.map { it.destination.route }.toSet() == setOf("library", "pdf-workspace")
    val route = entry?.destination?.route.orEmpty()
    val cameraRoute = route.startsWith("scanner/")
    val dockLens = androidx.compose.animation.core.animateFloatAsState(
        if (route == "pdf-workspace") 0f else 1f,
        tween(TabMotionDuration, easing = FastOutSlowInEasing),
        label = "Navigation lens"
    )
    FolioTheme(theme, cameraOverlay = cameraRoute, blurEnabled = blurEnabled, blurStrength = blurStrength,
        translucency = translucency) {
        // Each destination owns its system insets; nested Scaffolds must not add them twice.
        Scaffold(snackbarHost = { SnackbarHost(snack,Modifier.navigationBarsPadding()) }, contentWindowInsets = WindowInsets(0,0,0,0)) { outer ->
            Box(Modifier.fillMaxSize().padding(outer)) {
                NavHost(nav, "library", enterTransition={ fadeIn(tween(if (betweenTabs()) TabMotionDuration else 180)) },
                    exitTransition={ fadeOut(tween(if (betweenTabs()) TabMotionDuration else 130)) },
                    popEnterTransition={ fadeIn(tween(if (betweenTabs()) TabMotionDuration else 180)) },
                    popExitTransition={ fadeOut(tween(if (betweenTabs()) TabMotionDuration else 130)) }) {
                    composable("library") {
                        LibraryScreen(state, busy, model, open = { openDocument(it) }, settings = { nav.navigate("settings") }, recycle = { nav.navigate("recycle") }, openPage={ doc,page -> openDocument(doc,page) },text={ nav.navigate("text/$it") }, workspace={ nav.navigate("pdf-workspace") { launchSingleTop = true } }, importedPdf={ nav.navigate("pdf-import/$it") },review={ doc,draft -> nav.navigate("document/$doc"); nav.navigate("scan-edit/$draft") }, creating=creating, create=::beginDocument, dockLens=dockLens, motion=tabMotion(1, slidingTabs))
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
                        SettingsScreen(theme, { theme = it; preferences.edit { putString("theme", it) } },
                            { nav.popBackStack() }, { nav.navigate("backup") }, { nav.navigate("ocr-settings") },
                            setBlur = { blurEnabled = it; preferences.edit { putBoolean("glassBlurEnabled", it) } },
                            setBlurStrength = { blurStrength = it; preferences.edit { putInt("glassBlurStrength", it) } },
                            setTranslucency = { translucency = it; preferences.edit { putInt("glassTranslucency", it) } })
                    }
                    composable("backup") { BackupHome({ nav.popBackStack() }, { nav.navigate("local-backup") }, { nav.navigate("drive-backup") }) }
                    composable("local-backup") { LocalBackupScreen(model) { nav.popBackStack() } }
                    composable("drive-backup") { BackupScreen(model.backups) { nav.popBackStack() } }
                    composable("text/{id}") { entry -> ExtractedTextScreen(requireNotNull(entry.arguments?.getString("id")),null,model) { nav.popBackStack() } }
                    composable("ocr-settings") { OcrSettingsScreen(model) { nav.popBackStack() } }
                    composable("pdf-workspace") {
                        PdfWorkspace(null, model, { nav.popBackStack() }, { nav.navigate("pdf/$it") },
                            libraryDock = { nav.popBackStack("library", false) }, dockLens = dockLens,
                            motion = tabMotion(-1, slidingTabs))
                    }
                    composable("pdf-import/{session}?page={page}&match={match}",arguments=listOf(
                        androidx.navigation.navArgument("page") {type=androidx.navigation.NavType.IntType;defaultValue=0},
                        androidx.navigation.navArgument("match") {type=androidx.navigation.NavType.BoolType;defaultValue=false}
                    )) { entry ->
                        PdfWorkspace(null,model,{ nav.popBackStack() },{},initialSession=entry.arguments!!.getString("session").orEmpty(),
                            importIntoFolio=true,initialPage=entry.arguments!!.getInt("page"),
                            initialSearchMatch=entry.arguments!!.getBoolean("match"))
                    }

                }
                if (creating) NameDialog("New document", "Document name", "", busy, { creating = false }, creation = true,
                    validate = { documentNameConflict(it, state.documents) }) { name ->
                    model.run {
                        val id = model.repository.create(name, newDocumentFolder)
                        creating = false
                        openDocument(id)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                if (preparingPdf) FolioDialog(onDismissRequest = model::cancelPdfPreparation, title = { Text("Preparing PDF inputs") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Text("Saving safe copies of selected pages before background processing."); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }, confirmButton = { TextButton(onClick = model::cancelPdfPreparation) { Text("Cancel PDF preparation") } })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryScreen(state: LibraryState, busy: Boolean, model: LibraryViewModel, open: (String) -> Unit, settings: () -> Unit, recycle: () -> Unit,openPage:(String,String)->Unit,text:(String)->Unit, workspace:()->Unit,importedPdf:(String)->Unit,review:(String,String)->Unit,creating:Boolean,create:(String?)->Unit,dockLens:State<Float>,motion:Modifier) {
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
    var grid by rememberSaveable { mutableStateOf(prefs.getBoolean("libraryGrid",false)) }
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
    var datePickerOpen by rememberSaveable { mutableStateOf(false) }
    var filterDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterCreated by rememberSaveable { mutableStateOf(false) }
    val filterDay = filterDate?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    var sortMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var folderAction by remember { mutableStateOf<Folder?>(null) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var selected by remember { mutableStateOf<Document?>(null) }
    var selectionShare by rememberSaveable { mutableStateOf(false) }
    var selectionDelete by rememberSaveable { mutableStateOf(false) }
    fun toggle(id:String) { selection=if(id in selection) selection-id else selection+id }
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismissSearchKeyboard() {
        if (activeSearch) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    fun click(doc:Document) {
        if (selection.isNotEmpty()) toggle(doc.id)
        else { dismissSearchKeyboard(); open(doc.id) }
    }
    fun openMatch(hit: dev.folio.scanner.ocr.OcrSearchHit) {
        dismissSearchKeyboard()
        openPage(hit.documentId, hit.pageId)
    }
    LaunchedEffect(state.documents,state.loading) { if(!state.loading) selection=selection.filter { id -> state.documents.any { it.id==id } } }
    androidx.activity.compose.BackHandler(selection.isNotEmpty()) { selection=emptyList() }

    var action by remember { mutableStateOf("") }
    var share by rememberSaveable { mutableStateOf<String?>(null) }
    var print by rememberSaveable { mutableStateOf<String?>(null) }
    val expression=dev.folio.scanner.ocr.searchExpression(query)
    val recognizing by remember { model.repository.dao.observePendingOcrCount() }.collectAsStateWithLifecycle(0)
    val hits by remember(expression,query) { if(expression.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else model.repository.dao.searchOcr(expression,query.trim()) }.collectAsStateWithLifecycle(emptyList())
    val dateWindow = filterDay?.let { day ->
        val zone = ZoneId.systemDefault()
        day.atStartOfDay(zone).toInstant().toEpochMilli() until day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val eligible = state.documents.filter { doc ->
        (tab != 1 || doc.favorite) && (folder == null || doc.folderId == folder) &&
            (dateWindow == null || (if (filterCreated) doc.createdAt else doc.modifiedAt) in dateWindow)
    }
    val eligibleIds = eligible.mapTo(hashSetOf()) { it.id }
    val visibleHits = if (expression.isBlank()) emptyList() else hits.filter { it.documentId in eligibleIds }
    val matching = eligible.filter { it.title.contains(query, ignoreCase = true) }
    val recent = if (tab == 3) matching.sortedByDescending { it.modifiedAt }.sortedByDescending { it.favorite }.take(20) else matching
    val docs = when (sort) {
        "Name" -> recent.sortedBy { it.title.lowercase() }
        "Created" -> recent.sortedByDescending { it.createdAt }
        else -> recent.sortedByDescending { it.modifiedAt }
    }.sortedByDescending { it.favorite }
    fun changeLayout(asGrid: Boolean) {
        grid = asGrid
        prefs.edit { putBoolean("libraryGrid", asGrid) }
    }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val searchFocus = remember { FocusRequester() }
    var revealSearch by remember { mutableStateOf(false) }
    var headerProgress by remember { mutableFloatStateOf(0f) }
    var searchHeight by remember { mutableIntStateOf(0) }
    var detailsHeight by remember { mutableIntStateOf(0) }
    val headerScope = rememberCoroutineScope()
    var settlingHeader by remember { mutableStateOf<Job?>(null) }
    fun settleHeader(target: Float) {
        settlingHeader?.cancel()
        if (headerProgress == target) return
        settlingHeader = headerScope.launch {
            animate(headerProgress, target, animationSpec = tween(180, easing = FastOutSlowInEasing)) { value, _ ->
                headerProgress = value
            }
        }
    }
    fun resetDocumentScroll() {
        settleHeader(0f)
        listState.requestScrollToItem(0)
        gridState.requestScrollToItem(0)
    }
    var revealPinned by remember { mutableStateOf<String?>(null) }
    fun star(doc: Document) { model.run {
        model.repository.favorite(doc.id)
        if (!doc.favorite) revealPinned = doc.id
    } }
    LaunchedEffect(revealPinned, docs, state.documents, grid) {
        val id = revealPinned ?: return@LaunchedEffect
        val index = docs.indexOfFirst { it.id == id && it.favorite }
        if (index >= 0) {
            if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index)
        }
        if (state.documents.any { it.id == id && it.favorite }) revealPinned = null
    }
    fun actions(doc: Document) { if (selection.isEmpty()) selected = doc else toggle(doc.id) }
    fun hold(doc: Document) { toggle(doc.id); focus.clearFocus() }
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val imeStart = WindowInsets.imeAnimationSource
    val imeEnd = WindowInsets.imeAnimationTarget
    fun keyboardProgress(): Float {
        val current = ime.getBottom(density)
        val full = maxOf(current, imeStart.getBottom(density), imeEnd.getBottom(density))
        return if (full == 0) 0f else current.toFloat() / full
    }
    val keyboardOpen by remember(ime, imeEnd, density) {
        derivedStateOf { ime.getBottom(density) > 0 || imeEnd.getBottom(density) > 0 }
    }
    val browseSemantics = if (keyboardOpen) Modifier.clearAndSetSemantics {} else Modifier
    val closeSearchFraction = animateFloatAsState(
        if (activeSearch) 1f else 0f, tween(150, easing = FastOutSlowInEasing), label = "Close search action"
    )
    val foldGlassWeight = remember { {
        // Fade to the surface tint while the header changes height, then resume live sampling.
        val edgeDistance = minOf(headerProgress, 1f - headerProgress)
        (1f - edgeDistance / .18f).coerceIn(0f, 1f)
    } }
    val backdrop = rememberDockBackdrop(true, sampleScale = .5f, sampleWeight = foldGlassWeight)
    val haptic = LocalHapticFeedback.current
    val canCompact = docs.isNotEmpty() && tab != 2 && selection.isEmpty() && !revealSearch && !activeSearch && !keyboardOpen && filterDay == null
    var toolbarHeight by remember { mutableIntStateOf(0) }
    var tabsHeight by remember { mutableIntStateOf(0) }
    val headerState by remember {
        derivedStateOf {
            when (headerProgress) {
                0f -> "Expanded"
                1f -> "Compact"
                else -> "Transitioning"
            }
        }
    }
    val compactHeader = headerState == "Compact"
    val currentScroll by rememberUpdatedState<ScrollableState>(if (grid) gridState else listState)
    val compactOnScroll by rememberUpdatedState(canCompact)
    LaunchedEffect(canCompact) { if (!canCompact) settleHeader(0f) }
    val headerScroll = remember {
        object : NestedScrollConnection {
            private fun consume(delta: Float): Offset {
                val height = searchHeight + detailsHeight
                if (height == 0) return Offset.Zero
                val previous = headerProgress
                headerProgress = (previous - delta / height).coerceIn(0f, 1f)
                return Offset(0f, ((previous - headerProgress) * height).coerceIn(minOf(delta, 0f), maxOf(delta, 0f)))
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && compactOnScroll) settlingHeader?.cancel()
                if (!compactOnScroll || available.y >= -kotlin.math.abs(available.x)) return Offset.Zero
                return consume(available.y)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (compactOnScroll && available.y > 0f && !currentScroll.canScrollBackward) consume(available.y)
                else Offset.Zero

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (compactOnScroll && headerProgress > 0f && headerProgress < 1f) {
                    settleHeader(if (headerProgress >= .5f) 1f else 0f)
                }
                return Velocity.Zero
            }
        }
    }
    fun importDocuments() {
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        importer.launch(arrayOf("image/*", "application/pdf"))
    }
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    Scaffold(
        modifier = Modifier.workspaceSwipe(
            !busy && selection.isEmpty() && !activeSearch && !creating && importUris.isEmpty() &&
                selected == null && !newFolder && !datePickerOpen, true, workspace
        ).onPreInterceptKeyBeforeSoftKeyboard { event ->
            if (activeSearch && event.key == Key.Back) {
                if (event.type == KeyEventType.KeyUp) leaveSearch()
                true
            } else false
        },
        topBar = {
            val shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            FolioGlassSurface(motion.fillMaxWidth().testTag("library-header").semantics {
                stateDescription = headerState
            }, backdrop, shape = shape, raised = false, tintOpacity = .63f, showRim = false, sampleScale = 1f) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
                    if(selection.isNotEmpty()) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick={ selection=emptyList() }) { Icon(Icons.Outlined.Close,"Cancel document selection") }
                        Text("${selection.size} selected",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        IconButton(onClick={ selectionShare=true }) { Icon(Icons.Outlined.Share,"Share selected documents") }
                        IconButton(onClick={ selectionDelete=true }) { Icon(Icons.Outlined.Delete,"Delete selected documents") }
                    } else Row(Modifier.fillMaxWidth().onSizeChanged { toolbarHeight = it.height }
                        .statusBarsPadding().revealHeaderSize { 1f - keyboardProgress() }
                        .then(browseSemantics)
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(dev.folio.scanner.R.drawable.ic_folio_mark), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Folio", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val toolbarBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .82f))
                        val toolbarFill = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .44f)
                        val toolbarIconColors = IconButtonDefaults.outlinedIconButtonColors(containerColor = toolbarFill)
                        IconButton(onClick = { revealSearch = true }, enabled = compactHeader,
                            modifier = Modifier.revealHeaderSize(horizontal = true) { headerProgress }) {
                            Icon(Icons.Outlined.Search, "Open Library search")
                        }
                        OutlinedButton(enabled = !busy, shape = MaterialTheme.shapes.medium, border = toolbarBorder, onClick = ::importDocuments,
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = toolbarFill,
                            contentColor = MaterialTheme.colorScheme.onSurface), contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Icon(Icons.Outlined.FileOpen, if (compactHeader) "Import" else null, Modifier.size(20.dp))
                            Box(Modifier.revealHeaderSize(horizontal = true) { 1f - headerProgress }.padding(start = 4.dp)) {
                                Text("Import", maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        OutlinedIconButton(onClick = recycle, shape = MaterialTheme.shapes.medium, border = toolbarBorder, colors = toolbarIconColors) {
                            Icon(Icons.Outlined.DeleteOutline, "Recycle Bin", Modifier.size(20.dp))
                        }
                        OutlinedIconButton(onClick = settings, shape = MaterialTheme.shapes.medium, border = toolbarBorder, colors = toolbarIconColors) {
                            Icon(Icons.Outlined.Settings, "Settings", Modifier.size(20.dp))
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                        Column(Modifier.fillMaxWidth().revealHeaderSize { 1f - headerProgress }
                            .onSizeChanged { searchHeight = it.height }.padding(top = 4.dp)
                            .then(if (compactHeader) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                                OutlinedTextField(query, { query = it }, placeholder = { Text("Search documents") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                    trailingIcon = { Row {
                                        IconButton(onClick=::leaveSearch, enabled = activeSearch,
                                            modifier = Modifier.revealHeaderSize(horizontal = true) { closeSearchFraction.value }) {
                                            Icon(Icons.Outlined.Close, if (activeSearch) "Close search" else null)
                                        }
                                        IconButton(onClick = {
                                            if (keyboardOpen) changeLayout(!grid)
                                            else { focus.clearFocus(); datePickerOpen = true }
                                        }, modifier = Modifier.semantics {
                                            contentDescription = if (!keyboardOpen) "Filter by date" else if (grid) "List view" else "Grid view"
                                        }) {
                                            Crossfade(keyboardOpen, animationSpec = tween(120), label = "Search tool") { typing ->
                                                if (typing) Icon(if (grid) Icons.AutoMirrored.Filled.List else Icons.Outlined.GridView,
                                                    null)
                                                else Icon(Icons.Outlined.Tune, null,
                                                    tint = if (filterDay != null) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                                            }
                                        }
                                    } },
                                    keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
                                    keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={ focus.clearFocus() }),
                                    enabled = !compactHeader, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { if(it.isFocused) searching=true }, shape = androidx.compose.foundation.shape.CircleShape,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .80f),
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    ))
                                if (query.isNotBlank() && recognizing > 0) {
                                    Text("$recognizing pages still recognizing text…", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                LaunchedEffect(revealSearch, headerState) {
                                    if (revealSearch && !compactHeader) {
                                        searchFocus.requestFocus()
                                        revealSearch = false
                                    }
                                }
                        }
                            BoxWithConstraints(Modifier.fillMaxWidth().onSizeChanged { tabsHeight = it.height }
                                .revealHeaderSize { 1f - keyboardProgress() }
                                .then(browseSemantics).padding(top = 8.dp)) {
                                val fitPrimaryTabs = maxWidth >= 328.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.15f
                                val tabWidth = maxWidth - 16.dp
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("All documents", "Favorites", "Folders", "Recent").forEachIndexed { index, label ->
                                        val colors = MaterialTheme.colorScheme
                                        val active = tab == index
                                        val tabShape = MaterialTheme.shapes.medium
                                        Surface(
                                            modifier = Modifier.heightIn(min = 44.dp).then(
                                                if (fitPrimaryTabs && index < 3) Modifier.width(tabWidth * listOf(.405f, .31f, .285f)[index])
                                                else Modifier
                                            ).clip(tabShape).selectable(active, role = Role.Tab, onClick = { tab = index; folder = null }),
                                            shape = tabShape,
                                            color = if (active) colors.primaryContainer else colors.surfaceContainerHigh.copy(alpha = .34f),
                                            contentColor = if (active) colors.onPrimaryContainer else colors.onSurface,
                                            border = BorderStroke(1.dp, if (active) colors.primary.copy(alpha = .44f) else colors.outlineVariant)
                                        ) {
                                            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                                verticalAlignment = Alignment.CenterVertically) {
                                                Icon(listOf(Icons.Outlined.Description, Icons.Outlined.StarBorder,
                                                    Icons.Outlined.Folder, Icons.Outlined.Schedule)[index], null, Modifier.size(18.dp))
                                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
                                }
                            }
                            Column(Modifier.fillMaxWidth().revealHeaderSize { (1f - headerProgress) * (1f - keyboardProgress()) }
                                .then(browseSemantics)
                                .onSizeChanged { detailsHeight = it.height }.padding(top = 8.dp)
                                .then(if (compactHeader) Modifier.semantics { hideFromAccessibility() } else Modifier),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (tab == 2) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { newFolder = true }) { Text("New folder") }
                                }
                                else {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(state.folders.find { it.id == folder }?.name ?: if (tab == 1) "Favorites" else "Documents",
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.SemiBold)
                                            val pages = docs.sumOf { it.pageCount }
                                            Text("${docs.size} ${if (docs.size == 1) "document" else "documents"} · $pages ${if (pages == 1) "page" else "pages"}",
                                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        FolioGlassSurface(Modifier.width(100.dp), shape = MaterialTheme.shapes.medium, raised = false) {
                                            Row(Modifier.fillMaxWidth().selectableGroup()) {
                                                listOf(false, true).forEach { asGrid ->
                                                    val label = if (asGrid) "Grid" else "List"
                                                    val active = grid == asGrid
                                                    Box(Modifier.weight(1f).height(48.dp).clip(MaterialTheme.shapes.medium)
                                                        .selectable(active, role = Role.Tab, onClick = { changeLayout(asGrid) }),
                                                        contentAlignment = Alignment.Center) {
                                                        if (active) Box(Modifier.matchParentSize().padding(2.dp)
                                                            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium))
                                                        Icon(if (asGrid) Icons.Outlined.GridView else Icons.AutoMirrored.Filled.List,
                                                            if (active) "$label layout selected" else "$label view", Modifier.size(20.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (filterDay != null) {
                                        InputChip(
                                            selected = true, onClick = { filterDate = null; resetDocumentScroll() },
                                            label = { Text("${if (filterCreated) "Created" else "Modified"} · ${filterDay.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}") },
                                            leadingIcon = { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp)) },
                                            trailingIcon = { Icon(Icons.Outlined.Close, "Clear date filter", Modifier.size(16.dp)) }
                                        )
                                    }
                                    FolioGlassSurface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, raised = false) {
                                        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box {
                                                OutlinedButton(onClick = { sortMenu = true }, shape = MaterialTheme.shapes.medium,
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = .45f),
                                                        contentColor = MaterialTheme.colorScheme.onSurface),
                                                    contentPadding = PaddingValues(horizontal = 11.dp)) {
                                                    Text(sort)
                                                    Spacer(Modifier.width(4.dp))
                                                    Icon(if (sort == "Name") Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp))
                                                }
                                                DropdownMenu(sortMenu, { sortMenu = false }, shape = MaterialTheme.shapes.large,
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                                    listOf("Modified", "Created", "Name").forEach { label ->
                                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                                            sort = label
                                                            sortMenu = false
                                                            resetDocumentScroll()
                                                        })
                                                    }
                                                }
                                            }
                                            if (folder != null) TextButton(onClick = { folder = null }) { Text("Show all") }
                                            Spacer(Modifier.weight(1f))
                                            Checkbox(
                                                checked = docs.isNotEmpty() && docs.all { it.id in selection },
                                                onCheckedChange = { checked -> selection = if (checked) docs.map { it.id } else emptyList() },
                                                enabled = docs.isNotEmpty(),
                                                modifier = Modifier.semantics { contentDescription = "Select all documents" }
                                            )
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        },
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
                if (selection.isEmpty()) Box(Modifier.revealHeaderSize { 1f - keyboardProgress() }.then(browseSemantics)) {
                    FolioDock(false, !busy, backdrop, dockLens, library = {}, workspace = workspace, newDocument = { create(folder) })
                }
            }
        }
    ) { padding ->
        // Keep the lazy grid measured against the compact header during a drag. Its layer follows the fold.
        val moveContent = canCompact && toolbarHeight > 0 && tabsHeight > 0
        val compactTop = with(density) { (toolbarHeight + tabsHeight).toDp() } + 8.dp
        val listPadding = remember(padding, moveContent, compactTop) {
            object : PaddingValues {
                override fun calculateTopPadding() = (if (moveContent) compactTop else padding.calculateTopPadding()) + 8.dp
                override fun calculateBottomPadding() = padding.calculateBottomPadding() + 12.dp
                override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
                override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
            }
        }
        val dockInsets = remember(padding) {
            object : PaddingValues by PaddingValues() {
                override fun calculateBottomPadding() = padding.calculateBottomPadding()
            }
        }
        Column(Modifier.fillMaxSize().consumeWindowInsets(dockInsets).imePadding()
            .nestedScroll(headerScroll).clipToBounds().then(backdrop?.source ?: Modifier)
            .graphicsLayer {
                translationY = if (moveContent) (1f - headerProgress) * (searchHeight + detailsHeight) else 0f
            }.then(motion).padding(
            start = padding.calculateStartPadding(direction) + 16.dp,
            end = padding.calculateEndPadding(direction) + 16.dp
        )) {
            if (tab == 2) {
                LazyColumn(Modifier.weight(1f), contentPadding = listPadding) {
                    items(state.folders.filter { it.name.contains(query, true) }, key = { it.id }) { item ->
                        ListItem(headlineContent = { Text(item.name) }, leadingContent = { Icon(Icons.Outlined.Folder, null) },
                            supportingContent = { Text("${state.documents.count { it.folderId == item.id }} documents") },
                            trailingContent = { FolioOverflowButton("Folder actions for ${item.name}", { folderAction = item }) },
                            modifier = Modifier.clickable { folder = item.id; tab = 0 })
                    }
                    if (state.folders.isEmpty()) item { EmptyState(Icons.Outlined.Folder, "A place for everything", "Create folders for receipts, study notes, or work.") }
                }
            } else {
                when {
                    query.isBlank() && state.loading -> Box(Modifier.weight(1f).fillMaxWidth().padding(listPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    query.isBlank() && docs.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth().padding(listPadding), contentAlignment = Alignment.Center) {
                        if (filterDay != null) EmptyState(Icons.Outlined.CalendarToday, "No documents on this date",
                            "Choose another date or clear the filter.")
                        else EmptyState(Icons.Outlined.Description, if (tab == 1) "Keep the important ones close" else "Start with a document",
                            if (tab == 1) "Mark a document as a favorite to find it here." else "Create a document to start your library.")
                    }
                    grid -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f), state = gridState, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = listPadding) {
                        items(docs, key = { it.id }) { doc -> DocumentTile(doc, model, { click(doc) }, { actions(doc) }, doc.id in selection, { hold(doc) }, { star(doc) }) }
                        items(visibleHits, key = { "ocr-${it.pageId}" }) { hit -> SearchPageResult(hit, model, true) { openMatch(hit) } }
                        if (docs.isEmpty() && visibleHits.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { NoSearchMatches() }
                    }
                    else -> LazyColumn(Modifier.weight(1f), state = listState, contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(docs, key = { it.id }) { doc ->
                            LibraryDocumentRow(doc, model, doc.id in selection, { click(doc) }, { hold(doc) }, { star(doc) }, { actions(doc) })
                        }
                        items(visibleHits, key = { "ocr-${it.pageId}" }) { hit -> SearchPageResult(hit, model, false) { openMatch(hit) } }
                        if (docs.isEmpty() && visibleHits.isEmpty()) item { NoSearchMatches() }
                    }
                }
            }
        }
    }
    if (datePickerOpen) LibraryDatePicker(filterDate, filterCreated, { datePickerOpen = false }) { picked, created ->
        filterDate = picked
        filterCreated = created
        datePickerOpen = false
        if (tab == 2) tab = 0
        resetDocumentScroll()
    }
    val chosen=selection.mapNotNull { id -> state.documents.find { it.id==id } }
    if(selectionShare && chosen.isNotEmpty()) MultiDocumentShareSheet(chosen,model) { selectionShare=false }
    if(selectionDelete) FolioDialog(onDismissRequest={ selectionDelete=false },title={ Text("Move ${chosen.size} documents to Recycle Bin?") },text={ Text("Their pages stay available for restoration. Cloud backups remain until permanent deletion.") },confirmButton={ TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick={ model.run { chosen.forEach { model.repository.delete(it.id) }; selectionDelete=false; selection=emptyList() } }) { Text("Move to Recycle Bin") } },dismissButton={ TextButton(onClick={ selectionDelete=false }) { Text("Cancel") } })
    if(importUris.isNotEmpty() && importPdf) FolioDialog(onDismissRequest={ importUris=emptyList(); importPassword="" },title={ Text("Review PDF before import") },text={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) { Text("Review your PDF, then choose Save to Folio. Leaving an unsaved import asks for confirmation. Your source file stays intact."); OutlinedTextField(importPassword,{ importPassword=it },label={ Text("Owner password, if needed") },visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true) } },confirmButton={ Button(enabled=!busy,onClick={ val uri=android.net.Uri.parse(importUris.single()); val secret=importPassword; model.run { val id=model.utility.open("edit",listOf(uri),secret,initialImport=true); importUris=emptyList(); importPassword=""; importedPdf(id) } }) { Text("Review PDF") } },dismissButton={ TextButton(onClick={ importUris=emptyList(); importPassword="" }) { Text("Cancel") } })
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
                        "Favorite", "Remove favorite" -> { star(doc); selected = null }
                        else -> action = label
                    }
                })
            }
        }
        if (action == "Rename") NameDialog("Rename document", "Document name", doc.title, busy, { action = ""; selected=null },validate={ documentNameConflict(it,state.documents,doc.id) }) { name -> model.run { model.repository.rename(doc.id, name); selected = null; action = "" } }
        if (action == "Duplicate") NameDialog("Duplicate document", "Document name", doc.title.take(113)+" (copy)", busy, { action = ""; selected=null },validate={ documentNameConflict(it,state.documents) }) { name -> model.run { model.repository.duplicate(doc.id,name); selected=null; action="" } }
        if (action == "Delete") FolioDialog(onDismissRequest = { action = "" }, title = { Text("Move ${doc.title} to Recycle Bin?") }, text = { Text("Your pages and originals will be kept. You can restore this document from Recycle Bin.") },
            confirmButton = { TextButton(colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error),onClick = { model.run { model.repository.delete(doc.id); selected = null; action = "" } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { action = ""; selected=null }) { Text("Cancel") } })
        if (action == "Move to folder") FolioDialog(onDismissRequest = { action = "" }, title = { Text("Move to folder") }, text = {
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
private fun NoSearchMatches() {
    EmptyState(Icons.Outlined.Search, "No matches", "Try another name or word. Pages become searchable as recognition finishes.")
}

@Composable
private fun Modifier.selectionAppearance(chosen:Boolean)=this.semantics { selected=chosen }
    .background(if(chosen) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(8.dp))
    .border(if(chosen) 2.dp else 1.dp,if(chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(8.dp))

@Composable
private fun LibraryDatePicker(
    current: Long?, currentCreated: Boolean, dismiss: () -> Unit, apply: (Long, Boolean) -> Unit
) {
    val picker = rememberDatePickerState(initialSelectedDateMillis = current)
    var created by rememberSaveable { mutableStateOf(currentCreated) }
    DatePickerDialog(
        onDismissRequest = dismiss,
        colors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(
            alpha = LocalFolioBlur.current.opacity(.97f, .85f))),
        confirmButton = {
            FolioWindowBlur(confirmation = true)
            TextButton(onClick = { picker.selectedDateMillis?.let { apply(it, created) } }, enabled = picker.selectedDateMillis != null) {
                Text("Apply date")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } }
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Filter by date", Modifier.padding(start = 20.dp, top = 16.dp), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!created, { created = false }, label = { Text("Modified date") })
                FilterChip(created, { created = true }, label = { Text("Created date") })
            }
            val picked = picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            DatePicker(picker, title = null, headline = {
                Text(picked?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Select a day",
                    Modifier.padding(start = 20.dp), style = MaterialTheme.typography.titleMedium)
            })
        }
    }
}

private fun Modifier.revealHeaderSize(horizontal: Boolean = false, fraction: () -> Float): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val child = measurable.measure(constraints)
        val progress = fraction()
        val width = if (horizontal) (child.width * progress).roundToInt() else child.width
        val height = if (horizontal) child.height else (child.height * progress).roundToInt()
        layout(width, height) {
            child.placeRelativeWithLayer(0, 0) {
                alpha = ((progress - .45f) / .55f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
                if (!horizontal) translationY = -8.dp.toPx() * (1f - progress)
            }
        }
    }

@Composable
private fun LibraryStar(doc: Document, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (pressed) .94f else 1f, tween(120), label = "Star press")
    Box(Modifier.size(48.dp)
        .then(if (focused) Modifier.border(1.dp, colors.primary, MaterialTheme.shapes.medium) else Modifier)
        .toggleable(doc.favorite, interactionSource = interactions, indication = null,
            role = Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center) {
        Icon(if (doc.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            if (doc.favorite) "Unstar ${doc.title}" else "Star ${doc.title}",
            Modifier.size(20.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            tint = if (doc.favorite) colors.primary else colors.onSurfaceVariant)
    }
}

@Composable
private fun LibraryDocumentRow(
    doc: Document, model: LibraryViewModel, chosen: Boolean,
    open: () -> Unit, hold: () -> Unit, star: () -> Unit, actions: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { selected = chosen }
            .combinedClickable(onClick = open, onLongClick = hold),
        shape = MaterialTheme.shapes.large,
        color = if (chosen) colors.primaryContainer else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, if (chosen) colors.primary else colors.outlineVariant.copy(alpha = .66f))
    ) {
        Row(Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            DocumentCover(doc, model, Modifier.size(54.dp, 68.dp), fadeIn = false)
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${doc.pageCount} ${if (doc.pageCount == 1) "page" else "pages"} · ${date(doc.modifiedAt)}",
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (!chosen) LibraryStar(doc, star)
            if (chosen) IconButton(onClick = actions) { Icon(Icons.Outlined.CheckCircle, "Actions for ${doc.title}") }
            else FolioOverflowButton("Actions for ${doc.title}", actions)
        }
    }
}

@Composable
private fun DocumentTile(doc: Document, model: LibraryViewModel, open: () -> Unit, actions: () -> Unit, chosen:Boolean, hold:()->Unit, star:()->Unit) {
    val pages by remember(doc.id) { model.repository.dao.observePages(doc.id) }.collectAsStateWithLifecycle(emptyList())
    val thumbnailBackdrop = rememberDockBackdrop(true)
    Column(Modifier.selectionAppearance(chosen).combinedClickable(onClick=open,onLongClick=hold).padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.78f).contentOutline().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().then(thumbnailBackdrop?.source ?: Modifier), contentAlignment = Alignment.Center) {
                if (pages.isNotEmpty()) PageThumbnail(pages.first(),model,"Document preview",
                    Modifier.fillMaxSize().padding(4.dp), fadeIn = false)
                else Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.size(40.dp))
            }
            if(doc.importedPdf) PdfBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
            if(chosen) Icon(Icons.Outlined.CheckCircle,"Selected",tint=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.TopStart).padding(8.dp))
            FolioOverflowButton("Actions for ${doc.title}", actions, Modifier.align(Alignment.TopEnd), backdrop = thumbnailBackdrop)
        }
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text("${doc.pageCount} pages · ${date(doc.modifiedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!chosen) LibraryStar(doc, star)
        }
    }
}

@Composable
internal fun DocumentCover(doc: Document,model: LibraryViewModel,modifier: Modifier,fadeIn: Boolean = true) {
    val pages by remember(doc.id) { model.repository.dao.observePages(doc.id) }.collectAsStateWithLifecycle(emptyList())
    Box(modifier,contentAlignment=Alignment.Center) {
        if(pages.isNotEmpty()) PageThumbnail(pages.first(),model,"Document preview",Modifier.fillMaxSize(), fadeIn = fadeIn)
        else Icon(Icons.Outlined.Description,null,tint=MaterialTheme.colorScheme.outline)
        if(doc.importedPdf) PdfBadge(Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

@Composable
private fun SettingsScreen(theme: String, change: (String) -> Unit, back: () -> Unit, backup: () -> Unit, ocr: () -> Unit,
    setBlur: (Boolean) -> Unit, setBlurStrength: (Int) -> Unit, setTranslucency: (Int) -> Unit) {
    Scaffold(topBar = { FolioTopBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            FolioPanel {
                val haptic = LocalHapticFeedback.current
                Column(Modifier.selectableGroup()) {
                    listOf("System", "Light", "Dark", "AMOLED").forEachIndexed { index, mode ->
                        if (index != 0) MenuSeparator()
                        Row(Modifier.fillMaxWidth().background(if (theme == mode) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .selectable(theme == mode, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = {
                                if (mode != theme) { haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); change(mode) }
                            }).heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(theme == mode, onClick = null)
                            Text(mode, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            val glass = LocalFolioBlur.current
            FolioPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Background blur", style = MaterialTheme.typography.titleMedium)
                                Text("Blur behind navigation bars and dialogs", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = glass.enabled, onCheckedChange = setBlur, modifier = Modifier.semantics {
                                contentDescription = "Background blur"
                            })
                        }
                        FolioRulerDial("Blur strength", glass.strength.toFloat(), 25f..150f, 100f,
                            change = { setBlurStrength(it.roundToInt()) },
                            steps = 24, enabled = glass.enabled, displayValue = "${glass.strength}%")
                    }
                    FolioRulerDial("Translucency", glass.translucency.toFloat(), 0f..100f, 50f,
                        change = { setTranslucency(it.roundToInt()) }, steps = 19,
                        displayValue = "${glass.translucency}%")
                }
            }
            FolioPanel {
                Column {
                    val panels = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                    ListItem(headlineContent={ Text("Backup") },supportingContent={ Text("Local backup and Google Drive") },leadingContent={ Icon(Icons.Outlined.CloudUpload,null) },trailingContent={ Icon(Icons.Outlined.ChevronRight,null) },colors=panels,modifier=Modifier.clickable(onClick=backup))
                    MenuSeparator()
                    ListItem(headlineContent={ Text("Text recognition") },supportingContent={ Text("Offline · English printed documents") },leadingContent={ Icon(Icons.AutoMirrored.Outlined.TextSnippet,null) },trailingContent={ Icon(Icons.Outlined.ChevronRight,null) },colors=panels,modifier=Modifier.clickable(onClick=ocr))
                }
            }
            FolioPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Private by default", style = MaterialTheme.typography.titleMedium)
                    Text("Folio stores documents locally by default. Optional Google Drive backup uploads your Folio backup data only to the account you authorize. Uninstalling Folio removes its local documents.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
internal fun NameDialog(title: String, label: String, initial: String, busy: Boolean, dismiss: () -> Unit, optional: Boolean = false, validate: (String)->String? = { null }, creation: Boolean = false, save: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val problem = when { name.trim().length>120 -> "Use 120 characters or fewer."; name.isNotEmpty() && name.isBlank() && !optional -> "Enter a name."; else -> validate(name.trim()) }
    val saveName = { save(name.trim()) }
    val canSave = (optional || name.isNotBlank()) && problem == null && !busy
    FolioDialog(onDismissRequest = dismiss, title = {
        if (creation) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Outlined.CameraAlt, null, Modifier.padding(10.dp).size(22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(title)
            }
        } else Text(title)
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (creation) Text("Name your document, then add pages with the camera.")
            OutlinedTextField(
                name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
                singleLine = true, isError = problem != null,
                supportingText = problem?.let { message -> { Text(message, Modifier.semantics {
                    liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                }) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (canSave) saveName() })
            )
        }
    }, confirmButton = {
        if (creation) Button(onClick = saveName, enabled = canSave) { Text("Save") }
        else TextButton(onClick = saveName, enabled = canSave) { Text("Save") }
    }, dismissButton = {
        Row { if(optional) TextButton(onClick={ name="" }) { Text("Reset name") }; TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } }
    })
}

private fun date(time: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))

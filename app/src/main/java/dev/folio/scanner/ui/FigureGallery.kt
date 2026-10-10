@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*

@Composable internal fun FigureGallery(model:LibraryViewModel,session:String,count:Int,ready:Boolean,exporting:Boolean,modifier:Modifier=Modifier) {
    val utility=model.utility;var revision by remember {mutableIntStateOf(0)}
    val figures by produceState(emptyList<Figure>(),session,count,ready,revision) {value=withContext(Dispatchers.IO) {
        val edits=runCatching {JSONObject(File(utility.folder(session,create=false),"figure-bounds.json").readText())}.getOrDefault(JSONObject())
        (0 until count).flatMap {i ->runCatching {val page=AnalysisPage.parse(JSONObject(File(utility.folder(session,create=false),"analysis-$i.json").readText()));reconcileFigures(page).map {figure ->
            if(edits.has(figure.id)) {val box=boxFrom(edits.getJSONArray(figure.id));val format=figureFormat(page,box);figure.copy(box=box,format=format.first,objectIndex=format.second)} else figure
        }}.getOrDefault(emptyList())}
    }}
    var selected by rememberSaveable {mutableStateOf(emptyList<String>())};var preview by remember {mutableStateOf<Figure?>(null)}
    fun exportTo(uri:Uri) {model.run {withContext(Dispatchers.IO) {
        utility.rememberDestination("figures",uri)
        utility.exportAnalysis(session,uri,"figures",JSONObject().put("figures",JSONArray(figures.filter {it.id in selected}.map {it.json()})))
    }}}
    val folder=rememberLauncherForActivityResult(PdfDestinationPicker()) {uri ->if(uri!=null) exportTo(uri)}
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${selected.size} of ${figures.size} selected",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            TextButton(onClick={selected=if(selected.size==figures.size) emptyList() else figures.map {it.id}}) {Text(if(selected.size==figures.size && figures.isNotEmpty()) "Deselect all" else "Select all")}
        }
        if(figures.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {Text(if(ready) "No images or figures were found." else "Figures appear as pages finish.",Modifier.padding(24.dp))}
        else LazyVerticalGrid(GridCells.Adaptive(150.dp),Modifier.weight(1f),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            items(figures,key={it.id}) {figure ->
                val checked=figure.id in selected
                Surface(shape=MaterialTheme.shapes.medium,color=if(checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(if(checked) 2.dp else 1.dp,if(checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column {
                        FigureThumbnail(File(utility.folder(session,create=false),"analysis-${figure.page}.jpg"),figure,Modifier.fillMaxWidth().height(150.dp).clickable {preview=figure})
                        Row(Modifier.fillMaxWidth().clickable {selected=if(checked) selected-figure.id else selected+figure.id}.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Checkbox(checked,{selected=if(it) selected+figure.id else selected-figure.id},enabled=!exporting)
                            Column(Modifier.weight(1f)) {Text("Page ${figure.page+1}",style=MaterialTheme.typography.labelLarge);Text(figure.label.replace('_',' '),style=MaterialTheme.typography.bodySmall);Text(figure.format.label,style=MaterialTheme.typography.labelSmall)}
                        }
                    }
                }
            }
        }
        Button(onClick={model.run {val retained=withContext(Dispatchers.IO) {utility.rememberedDestination("figures")};if(retained==null) folder.launch(documentsLocation) else exportTo(retained)}},enabled=ready && selected.isNotEmpty() && !exporting,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)) {Text("Export selected figures")}
        TextButton(onClick={folder.launch(documentsLocation)},enabled=ready && selected.isNotEmpty() && !exporting,modifier=Modifier.align(Alignment.CenterHorizontally)) {Text("Choose another destination folder")}
    }
    preview?.let {figure ->
        val windowSize=LocalWindowInfo.current.containerSize
        val previewLimit=with(LocalDensity.current) {(windowSize.height.toDp()*.4f/fontScale).coerceIn(100.dp,360.dp)}
        var bounds by remember(figure.id) {mutableStateOf(figure.box)}
        val page by produceState<AnalysisPage?>(null,figure.page) {value=withContext(Dispatchers.IO) {AnalysisPage.parse(JSONObject(File(utility.folder(session,create=false),"analysis-${figure.page}.json").readText()))}}
        AlertDialog(onDismissRequest={preview=null},title={Text("Page ${figure.page+1} · ${figure.label.replace('_',' ')}")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Drag the corners to adjust this figure.",style=MaterialTheme.typography.bodySmall)
            page?.let {p ->BoxWithConstraints(Modifier.fillMaxWidth()) {
                FigureBoundary(File(utility.folder(session,create=false),"analysis-${figure.page}.jpg"),p,bounds,Modifier.fillMaxWidth().height((maxWidth*p.height.toFloat()/p.width).coerceAtMost(previewLimit))) {bounds=it}
            }}
            Text(page?.let {figureFormat(it,bounds).first.label} ?: figure.format.label,style=MaterialTheme.typography.labelMedium)
            if(figure.caption.isNotBlank()) Text(figure.caption,style=MaterialTheme.typography.bodySmall,maxLines=3)
        }},confirmButton={Button(onClick={model.run {withContext(Dispatchers.IO) {val file=File(utility.folder(session,create=false),"figure-bounds.json");val j=runCatching {JSONObject(file.readText())}.getOrDefault(JSONObject());j.put(figure.id,bounds.json());dev.folio.scanner.backup.FolioBackupRepository.atomicWrite(file,j.toString().toByteArray())};preview=null;revision++}},enabled=!exporting) {Text("Apply bounds")}},dismissButton={TextButton(onClick={preview=null}) {Text("Cancel")}})
    }
}

@Composable private fun FigureThumbnail(file:File,figure:Figure,modifier:Modifier) {
    val image by produceState<android.graphics.Bitmap?>(null,file.path,figure.box) {value=withContext(Dispatchers.IO) {
        val decoder=android.graphics.BitmapRegionDecoder.newInstance(file.path,false) ?: return@withContext null
        try {val b=figure.box;val x=b.left.toInt().coerceIn(0,decoder.width-1);val y=b.top.toInt().coerceIn(0,decoder.height-1)
            val rect=android.graphics.Rect(x,y,b.right.toInt().coerceIn(x+1,decoder.width),b.bottom.toInt().coerceIn(y+1,decoder.height))
            val options=BitmapFactory.Options().apply {inSampleSize=1;while(max(rect.width(),rect.height())/inSampleSize>500) inSampleSize*=2}
            decoder.decodeRegion(rect,options)
        } finally {decoder.recycle()}
    }}
    Box(modifier,contentAlignment=Alignment.Center) {image?.let {Image(it.asImageBitmap(),"Preview figure ${figure.id}",Modifier.fillMaxSize().padding(8.dp))}}
}

@Composable private fun FigureBoundary(file:File,page:AnalysisPage,box:Box,modifier:Modifier,update:(Box)->Unit) {
    val image by produceState<android.graphics.Bitmap?>(null,file.path) {value=withContext(Dispatchers.IO) {BitmapFactory.decodeFile(file.path)}}
    val color=MaterialTheme.colorScheme.primary;val current by rememberUpdatedState(box)
    var corner by remember {mutableIntStateOf(-1)}
    Box(modifier.pointerInput(page) {var dragging=current;detectDragGestures(onDragStart={point ->dragging=current;val fit=FitTransform.create(page.width,page.height,size.width.toFloat(),size.height.toFloat());val b=fit.map(dragging);val corners=listOf(Offset(b.left,b.top),Offset(b.right,b.top),Offset(b.right,b.bottom),Offset(b.left,b.bottom));corner=corners.indices.minByOrNull {(corners[it]-point).getDistance()}?.takeIf {(corners[it]-point).getDistance()<48.dp.toPx()} ?: -1},onDragEnd={corner=-1},onDragCancel={corner=-1}) {change,delta ->if(corner>=0) {
        change.consume();val fit=FitTransform.create(page.width,page.height,size.width.toFloat(),size.height.toFloat());val b=dragging;val dx=delta.x/fit.scale;val dy=delta.y/fit.scale
        dragging=Box(if(corner in listOf(0,3)) (b.left+dx).coerceIn(0f,b.right-8) else b.left,if(corner in listOf(0,1)) (b.top+dy).coerceIn(0f,b.bottom-8) else b.top,if(corner in listOf(1,2)) (b.right+dx).coerceIn(b.left+8,page.width.toFloat()) else b.right,if(corner in listOf(2,3)) (b.bottom+dy).coerceIn(b.top+8,page.height.toFloat()) else b.bottom)
        update(dragging)
    }}}) {
        image?.let {Image(it.asImageBitmap(),"Adjust figure boundary",Modifier.fillMaxSize())}
        Canvas(Modifier.fillMaxSize()) {val b=FitTransform.create(page.width,page.height,size.width,size.height).map(box);drawRect(color,Offset(b.left,b.top),Size(b.width,b.height),style=Stroke(2.dp.toPx()));listOf(Offset(b.left,b.top),Offset(b.right,b.top),Offset(b.right,b.bottom),Offset(b.left,b.bottom)).forEach {drawCircle(color,7.dp.toPx(),it)}}
    }
}

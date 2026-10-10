package dev.folio.scanner.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.folio.scanner.pdf.pdfFileStem
import dev.folio.scanner.pdfanalysis.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable internal fun WordConversionPanel(model:LibraryViewModel,session:String,count:Int,title:String,ready:Boolean,exporting:Boolean,modifier:Modifier=Modifier) {
    var mode by rememberSaveable(session) {mutableStateOf(WordMode.EDITABLE)}
    var filename by rememberSaveable(session,title) {mutableStateOf(title)}
    val utility=model.utility
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {uri ->if(uri!=null) model.run {withContext(Dispatchers.IO) {utility.exportAnalysis(session,uri,"word",JSONObject().put("mode",mode.name))}}}
    val image by produceState<android.graphics.Bitmap?>(null,session,ready) {value=withContext(Dispatchers.IO) {BitmapFactory.decodeFile(File(utility.folder(session,create=false),"analysis-0.jpg").path)}}
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("$count ${if(count==1) "page" else "pages"} · offline conversion",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        image?.let {Surface(shape=MaterialTheme.shapes.medium,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),color=MaterialTheme.colorScheme.surfaceContainerLow) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Image(it.asImageBitmap(),"Word source preview",Modifier.fillMaxWidth().height((maxWidth*it.height.toFloat()/it.width).coerceAtMost(240.dp)).padding(8.dp))
            }
        }}
        Text("Conversion mode",style=MaterialTheme.typography.titleSmall)
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            WordMode.entries.forEach {choice ->Surface(onClick={mode=choice},enabled=!exporting,shape=MaterialTheme.shapes.medium,color=if(mode==choice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,if(mode==choice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {RadioButton(mode==choice,{mode=choice},enabled=!exporting);Column(Modifier.weight(1f).padding(start=8.dp)) {
                    Text(if(choice==WordMode.EDITABLE) "Editable" else "Layout-preserving",style=MaterialTheme.typography.titleSmall)
                    Text(if(choice==WordMode.EDITABLE) "Reflowable paragraphs, headings, lists and table cells." else "Page positions and pagination, with editable text and tables. Exact visual fidelity varies.",style=MaterialTheme.typography.bodySmall)
                }}
            }}
        }
        OutlinedTextField(filename,{filename=it},label={Text("Output filename")},singleLine=true,enabled=!exporting,modifier=Modifier.fillMaxWidth(),suffix={Text(".docx")})
        Text("Ruled tables become editable cells. Unruled or ambiguous tables remain editable text, with a conversion warning.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick={picker.launch(pdfFileStem(filename)+".docx")},enabled=ready && !exporting && filename.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Text("Convert and export DOCX")}
    }
}

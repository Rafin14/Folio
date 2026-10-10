package dev.folio.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun PdfSourceDialog(title:String,storage:()->Unit,folio:()->Unit,dismiss:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Choose a PDF source",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(Triple("Select from Storage",Icons.Outlined.FolderOpen,storage),Triple("Folio Documents",Icons.Outlined.Description,folio)).forEach {(label,icon,action) ->
            Surface(onClick=action,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=.92f),border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(24.dp));Spacer(Modifier.width(12.dp));Text(label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall);Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp))
                }
            }
        }
    }},confirmButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}

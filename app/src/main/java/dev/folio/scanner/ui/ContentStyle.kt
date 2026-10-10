package dev.folio.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** One outline treatment for document surfaces; selection keeps its stronger border. */
@Composable
internal fun Modifier.contentOutline() = border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))

@Composable
internal fun MenuSeparator() {
    HorizontalDivider(Modifier.padding(horizontal=16.dp), thickness=.5.dp, color=MaterialTheme.colorScheme.outlineVariant)
}

/** Shared contextual menu surface; action callbacks remain owned by each screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolioOverflowMenu(expanded:Boolean,dismiss:()->Unit,title:String,content:@Composable ColumnScope.()->Unit) {
    if(!expanded) return
    ModalBottomSheet(onDismissRequest=dismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surfaceContainer.copy(alpha=.98f),contentColor=MaterialTheme.colorScheme.onSurface,dragHandle=null) {
        Row(Modifier.fillMaxWidth().padding(start=24.dp,end=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
            FilledTonalIconButton(onClick=dismiss) {Icon(Icons.Outlined.Close,"Close menu")}
        }
        MenuSeparator()
        Column(Modifier.fillMaxWidth().weight(1f,false).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha=.9f),border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant)) {
                Column(content=content)
            }
        }
    }
}

@Composable
internal fun FolioMenuItem(label:String,onClick:()->Unit,enabled:Boolean=true) {
    val destructive=label.startsWith("Delete") || label.startsWith("Discard")
    val icon=when {
        destructive -> Icons.Outlined.Delete
        label.contains("Print") -> Icons.Outlined.Print
        label.contains("Share") -> Icons.Outlined.Share
        label.contains("Rename") -> Icons.Outlined.DriveFileRenameOutline
        label.contains("Duplicate") -> Icons.Outlined.ContentCopy
        label.contains("Rotate") -> Icons.Outlined.Rotate90DegreesCw
        label.contains("Text") || label.contains("OCR") -> Icons.Outlined.TextSnippet
        label.contains("Word") -> Icons.Outlined.Description
        label.contains("Favorite",true) -> Icons.Outlined.StarOutline
        label.contains("folder",true) -> Icons.Outlined.FolderOpen
        label.contains("page",true) || label.contains("gallery",true) -> Icons.Outlined.ViewList
        label.contains("filter",true) -> Icons.Outlined.Tune
        label.contains("Export") || label.contains("PDF") -> Icons.Outlined.PictureAsPdf
        label.contains("Edit") || label.contains("retake") -> Icons.Outlined.Edit
        else -> Icons.Outlined.ChevronRight
    }
    DropdownMenuItem(text={Text(label,style=MaterialTheme.typography.bodyLarge)},leadingIcon={Icon(icon,null,Modifier.size(24.dp))},onClick=onClick,enabled=enabled,
        modifier=Modifier.fillMaxWidth().heightIn(min=56.dp),colors=MenuDefaults.itemColors(textColor=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,leadingIconColor=if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant))
}

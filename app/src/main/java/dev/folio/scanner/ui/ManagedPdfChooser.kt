@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.folio.scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ManagedPdfChooser(model:LibraryViewModel,dismiss:()->Unit,select:(String)->Unit) {
    FolioPageChooser(model,false,dismiss,pdfOnly=true,selectDocument=select) {}
}

@Composable internal fun PdfBadge(modifier:Modifier=Modifier) {
    Surface(modifier,shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.secondaryContainer) {
        Text("PDF",Modifier.padding(horizontal=6.dp,vertical=3.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

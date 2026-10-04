package dev.folio.scanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.scanner.data.*
import dev.folio.scanner.ocr.OcrSearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SearchPageResult(hit: OcrSearchHit, model: LibraryViewModel, grid: Boolean, open: () -> Unit) {
    val page by produceState<Page?>(null,hit.pageId,hit.thumbnailUri,hit.pageName) { value=withContext(Dispatchers.IO) { model.repository.dao.page(hit.pageId) } }
    val label=hit.pageName ?: "Page ${hit.position+1}"
    if(grid) Column(Modifier.fillMaxWidth().clickable(onClick=open)) {
        page?.let { PageThumbnail(it,model,"Search preview $label",Modifier.fillMaxWidth().aspectRatio(.78f)) }
        Text(hit.title,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text(hit.snippet,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
    } else ListItem(headlineContent={ Text(hit.title,maxLines=1,overflow=TextOverflow.Ellipsis) },overlineContent={ Text(label) },supportingContent={ Text(hit.snippet,maxLines=2,overflow=TextOverflow.Ellipsis) },leadingContent={ page?.let { PageThumbnail(it,model,"Search preview $label",Modifier.size(56.dp,72.dp)) } },modifier=Modifier.clickable(onClick=open))
}

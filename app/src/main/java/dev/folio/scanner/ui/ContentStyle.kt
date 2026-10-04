package dev.folio.scanner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One outline treatment for document surfaces; selection keeps its stronger border. */
@Composable
internal fun Modifier.contentOutline() = border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))

@Composable
internal fun MenuSeparator() {
    HorizontalDivider(Modifier.padding(horizontal=16.dp), thickness=.5.dp, color=MaterialTheme.colorScheme.outlineVariant)
}

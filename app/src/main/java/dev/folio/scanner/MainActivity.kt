package dev.folio.scanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.folio.scanner.ui.FolioApp
import dev.folio.scanner.ui.LibraryViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: LibraryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FolioApp(model) }
    }
}

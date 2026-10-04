package dev.folio.scanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.scanner.data.*
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LibraryState(val documents: List<Document> = emptyList(), val folders: List<Folder> = emptyList(), val loading: Boolean = true)

@HiltViewModel
class LibraryViewModel @Inject constructor(val repository: DocumentRepository, val pipeline: dev.folio.scanner.processing.ImagePipeline, val pdfs: dev.folio.scanner.pdf.PdfRepository, val backups: dev.folio.scanner.backup.FolioBackupRepository, val ocr:dev.folio.scanner.ocr.OcrRepository, val utility:dev.folio.scanner.pdf.PdfUtility) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val preparingPdf = MutableStateFlow(false)
    private var pdfPreparation: kotlinx.coroutines.Job? = null
    private val operations = Mutex()
    val state = combine(repository.documents, repository.folders) { docs, folders -> LibraryState(docs, folders, false) }
        .catch { error.value = "Could not load your library. Close and reopen Folio to retry."; emit(LibraryState(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())
    init { run { repository.recoverDeletes(); repository.recoverCaptures(); repository.cleanShareCache(); pdfs.recoverPrepared(); pdfs.cleanTemporaryResults(); utility.recover() } }
    fun preparePdf(action: suspend () -> Unit) {
        pdfPreparation = run { preparingPdf.value = true; try { action() } finally { preparingPdf.value = false } }
    }
    fun cancelPdfPreparation() { pdfPreparation?.cancel() }
    fun run(action: suspend () -> Unit): kotlinx.coroutines.Job {
        return viewModelScope.launch {
            operations.withLock {
            busy.value = true
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: OutOfMemoryError) { error.value = "Not enough memory. Close other apps or choose a smaller image, then retry." }
            catch (failure: Exception) {
                android.util.Log.e("Folio", "Document operation failed", failure)
                error.value = if (failure is IllegalArgumentException) failure.message else "Could not complete this action. Check available storage and try again."
            }
            finally { busy.value = false }
            }
        }
    }
}

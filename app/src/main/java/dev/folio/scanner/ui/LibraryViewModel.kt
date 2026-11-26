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
    val localBackupRunning = MutableStateFlow(false)
    val localBackupFeedback = MutableStateFlow<String?>(null)
    private var localBackupJob: kotlinx.coroutines.Job? = null
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
    fun runLocalBackup(isImport: Boolean, action: suspend () -> Int) {
        if (localBackupJob?.isActive == true) return
        localBackupJob = run {
            localBackupRunning.value = true
            localBackupFeedback.value = null
            try {
                val count = action()
                val documents = if (count == 1) "1 document" else "$count documents"
                localBackupFeedback.value = if (isImport) "Imported $documents." else "Exported $documents."
            } catch (cancel: CancellationException) {
                localBackupFeedback.value = if (isImport) "Import interrupted. Check the library before retrying."
                else "Export cancelled. The destination file may be incomplete."
                throw cancel
            } catch (failure: Exception) {
                android.util.Log.w("FolioBackup", "Local backup failed", failure)
                localBackupFeedback.value = when (failure) {
                    is IllegalArgumentException, is IllegalStateException -> failure.message
                    is java.util.zip.ZipException, is org.json.JSONException -> "The backup file is invalid or incomplete."
                    else -> "Could not complete the backup. Check the file and available storage."
                }
            } finally {
                localBackupRunning.value = false
            }
        }
    }
    fun cancelLocalBackup() { localBackupJob?.cancel() }
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

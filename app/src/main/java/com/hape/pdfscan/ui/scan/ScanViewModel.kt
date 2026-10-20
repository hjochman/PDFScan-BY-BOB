package com.hape.pdfscan.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hape.pdfscan.pdf.PdfWriter
import com.hape.pdfscan.scan.AutoCaptureController
import com.hape.pdfscan.scan.AutoCaptureState
import com.hape.pdfscan.scan.DetectionResult
import com.hape.pdfscan.storage.NameValidator
import com.hape.pdfscan.storage.ScanFileRepository
import com.hape.pdfscan.storage.ScanFolderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ScanViewModel"

// ---------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------

/**
 * A single scanned page: the JPEG cache file and the in-memory bitmap.
 * The bitmap is kept alive so the page thumbnail can be shown without re-decoding.
 */
data class ScannedPage(
    val jpegFile: File,
    val bitmap: Bitmap,
)

// ---------------------------------------------------------------------------
// UI states
// ---------------------------------------------------------------------------

/** UI states for the Scan screen. */
sealed interface ScanUiState {

    /** Camera permission has not been granted yet. */
    data class PermissionDenied(val isPermanentlyDenied: Boolean) : ScanUiState

    /** Camera is running; [latestDetection] is null before the first analysis frame. */
    data class Scanning(val latestDetection: DetectionResult? = null) : ScanUiState

    /**
     * A photo has been captured and cropped.
     * [pages] holds all previously confirmed pages plus the new one ([newPage]).
     * The user can add more pages or finish.
     */
    data class PagePreview(
        val newPage: ScannedPage,
        val pages: List<ScannedPage>,
    ) : ScanUiState

    /**
     * The user tapped "Done"; the naming dialog is shown.
     * [pages] holds all confirmed pages (not yet written to PDF).
     * [nameError] is set when the last submitted name failed validation.
     * [isSaving] is true while the PDF is being written.
     */
    data class Naming(
        val pages: List<ScannedPage>,
        val nameError: Int? = null,
        val isSaving: Boolean = false,
    ) : ScanUiState

    /** Something unexpected went wrong (e.g. camera bind failure). */
    data class Error(val messageRes: Int) : ScanUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class ScanViewModel(
    private val folderSettings: ScanFolderSettings? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Scanning())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    // Diagnostic line for field tests; only shown in debuggable builds.
    private val _diagnostics = MutableStateFlow("")
    val diagnostics: StateFlow<String> = _diagnostics.asStateFlow()

    // Navigation signal: set to the saved PDF file after a successful save so the
    // screen can pop back to the main menu.
    private val _savedPdf = MutableStateFlow<File?>(null)
    val savedPdf: StateFlow<File?> = _savedPdf.asStateFlow()

    // ------------------------------------------------------------------
    // Auto-capture countdown
    // ------------------------------------------------------------------

    /** Callback set by the screen so the auto-capture controller can trigger a real capture. */
    var onAutoCaptureFire: (() -> Unit)? = null

    val autoCaptureController = AutoCaptureController(
        scope = viewModelScope,
        onFire = {
            if (_uiState.value is ScanUiState.Scanning) {
                onAutoCaptureFire?.invoke()
            }
        },
    )

    val autoCaptureState: StateFlow<AutoCaptureState> = autoCaptureController.state

    init {
        // Keep the countdown duration in sync with the persisted setting
        if (folderSettings != null) {
            viewModelScope.launch {
                folderSettings.countdownSeconds.collect { seconds ->
                    autoCaptureController.countdownSeconds = seconds
                    if (seconds <= 0) autoCaptureController.reset()
                }
            }
        }
    }

    fun onDiagnostics(text: String) {
        _diagnostics.value = text
    }

    // ------------------------------------------------------------------
    // Permission
    // ------------------------------------------------------------------

    fun onPermissionResult(granted: Boolean, isPermanentlyDenied: Boolean) {
        if (granted) {
            _uiState.value = ScanUiState.Scanning()
        } else {
            _uiState.value = ScanUiState.PermissionDenied(isPermanentlyDenied)
        }
    }

    fun onPermissionGranted() {
        _uiState.value = ScanUiState.Scanning()
    }

    // ------------------------------------------------------------------
    // Live detection updates (called from analysis thread via post)
    // ------------------------------------------------------------------

    fun onDetectionResult(result: DetectionResult) {
        // Update state and feed auto-capture atomically: only pass to the controller
        // if we are still in Scanning state after the update (avoids a brief restart
        // of the countdown while the camera pipeline is still delivering frames
        // just before the PagePreview state becomes visible).
        var wasScanning = false
        _uiState.update { current ->
            if (current is ScanUiState.Scanning) {
                wasScanning = true
                current.copy(latestDetection = result)
            } else current
        }
        if (wasScanning) {
            autoCaptureController.onDetectionResult(result)
        }
    }

    // ------------------------------------------------------------------
    // Capture → PagePreview
    // ------------------------------------------------------------------

    /**
     * Called after successful photo capture + crop.
     * Adds the new page on top of any previously confirmed pages and shows [ScanUiState.PagePreview].
     */
    fun onCaptureDone(croppedBitmap: Bitmap, tempFile: File) {
        autoCaptureController.reset()
        val newPage = ScannedPage(tempFile, croppedBitmap)
        val existing = currentPages()
        _uiState.value = ScanUiState.PagePreview(newPage = newPage, pages = existing)
    }

    /** Called when the camera or capture pipeline signals an error. */
    fun onCameraError(messageRes: Int) {
        autoCaptureController.reset()
        _uiState.value = ScanUiState.Error(messageRes)
    }

    // ------------------------------------------------------------------
    // PagePreview actions
    // ------------------------------------------------------------------

    /**
     * User confirmed the current page and wants to scan another one.
     * The new page is appended to the list; the camera restarts.
     */
    fun onAddAnotherPage() {
        val state = _uiState.value as? ScanUiState.PagePreview ?: return
        _uiState.value = ScanUiState.Scanning(latestDetection = null)
        // Pages list now includes the confirmed page
        // (stored transitionally in the state; we carry them via the full list)
        // We keep them alive in PagePreview.pages so we rebuild from there:
        _confirmedPages.clear()
        _confirmedPages.addAll(state.pages + state.newPage)
    }

    /**
     * User discards the just-captured page and retakes it (camera restarts, page not added).
     */
    fun onDiscardPage() {
        val state = _uiState.value as? ScanUiState.PagePreview ?: return
        state.newPage.bitmap.recycle()
        state.newPage.jpegFile.delete()
        _uiState.value = ScanUiState.Scanning(latestDetection = null)
    }

    /**
     * User is done scanning; show naming dialog.
     * The current page is confirmed and added to the list.
     */
    fun onFinishScanning() {
        val state = _uiState.value as? ScanUiState.PagePreview ?: return
        val allPages = state.pages + state.newPage
        _confirmedPages.clear()
        _confirmedPages.addAll(allPages)
        _uiState.value = ScanUiState.Naming(pages = allPages)
    }

    // ------------------------------------------------------------------
    // Naming → save PDF
    // ------------------------------------------------------------------

    /**
     * User submitted a document name.
     * [name] is the raw name without `.pdf` extension.
     * [targetDirectory] is the folder to save into (from the scan navigation argument).
     * [repository] is used only for the duplicate check; the actual write goes through [PdfWriter].
     */
    fun onSavePdf(name: String, targetDirectory: File, repository: ScanFileRepository) {
        val namingState = _uiState.value as? ScanUiState.Naming ?: return
        if (namingState.isSaving) return

        // Validate name (without extension)
        val problem = NameValidator.validate(name)
        if (problem != null) {
            val errorRes = com.hape.pdfscan.ui.main.StorageErrorMapper.mapNameProblem(problem)
            _uiState.value = namingState.copy(nameError = errorRes)
            return
        }

        val pdfName = if (name.endsWith(".pdf", ignoreCase = true)) name else "$name.pdf"

        // Validate the full filename including extension
        val fullProblem = NameValidator.validate(pdfName)
        if (fullProblem != null) {
            val errorRes = com.hape.pdfscan.ui.main.StorageErrorMapper.mapNameProblem(fullProblem)
            _uiState.value = namingState.copy(nameError = errorRes)
            return
        }

        _uiState.value = namingState.copy(nameError = null, isSaving = true)

        val pages = namingState.pages
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Duplicate check
                val target = File(targetDirectory, pdfName)
                if (target.exists()) {
                    return@withContext null // signal duplicate
                }
                // Ensure the target directory exists
                targetDirectory.mkdirs()
                // Load bitmaps from cache files if in-memory bitmaps were recycled
                val bitmaps = pages.map { page ->
                    if (!page.bitmap.isRecycled) page.bitmap
                    else BitmapFactory.decodeFile(page.jpegFile.absolutePath)
                }
                if (bitmaps.any { it == null }) {
                    return@withContext false
                }
                @Suppress("UNCHECKED_CAST")
                PdfWriter.write(bitmaps as List<android.graphics.Bitmap>, target)
                    .let { if (it) target else null }
            }

            when {
                result == null -> {
                    // Duplicate
                    _uiState.update { current ->
                        if (current is ScanUiState.Naming)
                            current.copy(
                                nameError = com.hape.pdfscan.R.string.error_already_exists,
                                isSaving = false,
                            )
                        else current
                    }
                }
                result == false -> {
                    _uiState.update { current ->
                        if (current is ScanUiState.Naming)
                            current.copy(
                                nameError = com.hape.pdfscan.R.string.error_pdf_save,
                                isSaving = false,
                            )
                        else current
                    }
                }
                else -> {
                    cleanupPages(pages)
                    _confirmedPages.clear()
                    _savedPdf.value = result as File
                }
            }
        }
    }

    /** User cancelled the naming dialog and went back to scanning (last page preview). */
    fun onCancelNaming() {
        val state = _uiState.value as? ScanUiState.Naming ?: return
        val pages = state.pages
        if (pages.isEmpty()) {
            // No pages yet – go back to camera
            _uiState.value = ScanUiState.Scanning()
            return
        }
        // Re-show the last page preview
        val lastPage = pages.last()
        _uiState.value = ScanUiState.PagePreview(
            newPage = lastPage,
            pages = pages.dropLast(1),
        )
    }

    // ------------------------------------------------------------------
    // Internal page list (bridge between PagePreview and Naming states)
    // ------------------------------------------------------------------

    private val _confirmedPages = mutableListOf<ScannedPage>()

    private fun currentPages(): List<ScannedPage> = _confirmedPages.toList()

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    private fun cleanupPages(pages: List<ScannedPage>) {
        for (page in pages) {
            try { page.bitmap.recycle() } catch (_: Exception) {}
            try { page.jpegFile.delete() } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        autoCaptureController.reset()
        // Clean up everything still in memory
        val state = _uiState.value
        when (state) {
            is ScanUiState.PagePreview -> {
                cleanupPages(state.pages + state.newPage)
            }
            is ScanUiState.Naming -> {
                cleanupPages(state.pages)
            }
            else -> {}
        }
        cleanupPages(_confirmedPages)
        _confirmedPages.clear()
    }
}

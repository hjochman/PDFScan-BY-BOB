package com.hape.pdfscan.ui.scan

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hape.pdfscan.pdf.PdfWriter
import com.hape.pdfscan.scan.AutoCaptureController
import com.hape.pdfscan.scan.AutoCaptureState
import com.hape.pdfscan.scan.CornerSmoother
import com.hape.pdfscan.scan.DetectionResult
import com.hape.pdfscan.scan.DocumentCorners
import com.hape.pdfscan.scan.FloatPoint
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
 * [rawFile] holds the uncropped original photo so the crop corners can be adjusted manually.
 */
data class ScannedPage(
    val jpegFile: File,
    val bitmap: Bitmap,
    val rawFile: File? = null,
    val detectedCorners: DocumentCorners? = null,
)

// ---------------------------------------------------------------------------
// UI states
// ---------------------------------------------------------------------------

/** UI states for the Scan screen. */
sealed interface ScanUiState {

    /** Camera permission has not been granted yet. */
    data class PermissionDenied(val isPermanentlyDenied: Boolean) : ScanUiState

    /** Camera is running; [latestDetection] is null before the first analysis frame. [isCapturing] is true while a photo is being processed. */
    data class Scanning(
        val latestDetection: DetectionResult? = null,
        val isCapturing: Boolean = false,
    ) : ScanUiState

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
     * Interactive manual crop adjustment on the full-resolution uncropped photo.
     */
    data class AdjustCrop(
        val newPage: ScannedPage,
        val pages: List<ScannedPage>,
        val initialCorners: DocumentCorners,
        val rawBitmap: Bitmap,
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

    /** Longest side of a cropped page in px; kept in sync with the persisted setting. */
    @Volatile var maxDimension: Int = ScanFolderSettings.DEFAULT_MAX_DIMENSION
        private set

    /** JPEG quality (percent) of a cropped page; kept in sync with the persisted setting. */
    @Volatile var jpegQuality: Int = ScanFolderSettings.DEFAULT_JPEG_QUALITY
        private set

    // Smoothing of the live overlay corners; strength 0..10 comes from the persisted setting.
    private val cornerSmoother = CornerSmoother()

    private val _smoothingStrength = MutableStateFlow(ScanFolderSettings.DEFAULT_SMOOTHING_STRENGTH)
    val smoothingStrength: StateFlow<Int> = _smoothingStrength.asStateFlow()

    init {
        cornerSmoother.strength = ScanFolderSettings.DEFAULT_SMOOTHING_STRENGTH
        // Keep the countdown duration in sync with the persisted setting
        if (folderSettings != null) {
            viewModelScope.launch {
                folderSettings.countdownSeconds.collect { seconds ->
                    autoCaptureController.countdownSeconds = seconds
                    if (seconds <= 0) autoCaptureController.reset()
                }
            }
            viewModelScope.launch {
                folderSettings.maxDimension.collect { maxDimension = it }
            }
            viewModelScope.launch {
                folderSettings.jpegQuality.collect { jpegQuality = it }
            }
            viewModelScope.launch {
                folderSettings.smoothingStrength.collect { applySmoothingStrength(it) }
            }
        }
    }

    /** Sets the smoothing strength (0..10) locally and persists it. */
    fun onSmoothingStrengthChanged(strength: Int) {
        applySmoothingStrength(strength)
        val settings = folderSettings ?: return
        val value = _smoothingStrength.value
        viewModelScope.launch { settings.setSmoothingStrength(value) }
    }

    private fun applySmoothingStrength(strength: Int) {
        val value = strength.coerceIn(CornerSmoother.MIN_STRENGTH, CornerSmoother.MAX_STRENGTH)
        cornerSmoother.strength = value
        _smoothingStrength.value = value
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
        if (result.isFallback) cornerSmoother.reset()
        val smoothed = if (result.isFallback) {
            result
        } else {
            result.copy(
                corners = cornerSmoother.smooth(result.corners, result.imageWidth, result.imageHeight),
            )
        }
        var wasScanning = false
        _uiState.update { current ->
            if (current is ScanUiState.Scanning) {
                wasScanning = !current.isCapturing
                current.copy(latestDetection = smoothed)
            } else current
        }
        if (wasScanning) {
            autoCaptureController.onDetectionResult(smoothed)
        }
    }

    /** Called when photo capture begins. */
    fun onCaptureStarted() {
        autoCaptureController.reset()
        _uiState.update { current ->
            if (current is ScanUiState.Scanning) current.copy(isCapturing = true) else current
        }
    }

    // ------------------------------------------------------------------
    // Capture → PagePreview
    // ------------------------------------------------------------------

    /**
     * Called after successful photo capture + crop.
     * Adds the new page on top of any previously confirmed pages and shows [ScanUiState.PagePreview].
     */
    fun onCaptureDone(
        croppedBitmap: Bitmap,
        tempFile: File,
        rawFile: File? = null,
        detectedCorners: DocumentCorners? = null,
    ) {
        autoCaptureController.reset()
        cornerSmoother.reset()
        val newPage = ScannedPage(tempFile, croppedBitmap, rawFile, detectedCorners)
        val existing = currentPages()
        _uiState.value = ScanUiState.PagePreview(newPage = newPage, pages = existing)
    }

    /** Called when the camera or capture pipeline signals an error. */
    fun onCameraError(messageRes: Int) {
        autoCaptureController.reset()
        cornerSmoother.reset()
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
        cornerSmoother.reset()
        _uiState.value = ScanUiState.Scanning(latestDetection = null)
        // Pages list now includes the confirmed page
        // (stored transitionally in the state; we carry them via the full list)
        // We keep them alive in PagePreview.pages so we rebuild from there:
        _confirmedPages.clear()
        _confirmedPages.addAll(state.pages + state.newPage)
    }

    /**
     * User opens the manual crop adjustment screen for the newly captured page.
     */
    fun onOpenCropAdjustment() {
        val state = _uiState.value as? ScanUiState.PagePreview ?: return
        val rawFile = state.newPage.rawFile ?: return
        if (!rawFile.exists()) return
        val initialCorners = state.newPage.detectedCorners
        viewModelScope.launch(Dispatchers.IO) {
            val rawBitmap = android.graphics.BitmapFactory.decodeFile(rawFile.absolutePath) ?: return@launch
            val corners = initialCorners ?: DocumentCorners(
                topLeft = com.hape.pdfscan.scan.FloatPoint(0.05f * rawBitmap.width, 0.05f * rawBitmap.height),
                topRight = com.hape.pdfscan.scan.FloatPoint(0.95f * rawBitmap.width, 0.05f * rawBitmap.height),
                bottomRight = com.hape.pdfscan.scan.FloatPoint(0.95f * rawBitmap.width, 0.95f * rawBitmap.height),
                bottomLeft = com.hape.pdfscan.scan.FloatPoint(0.05f * rawBitmap.width, 0.95f * rawBitmap.height),
            )
            withContext(Dispatchers.Main) {
                _uiState.value = ScanUiState.AdjustCrop(
                    newPage = state.newPage,
                    pages = state.pages,
                    initialCorners = corners,
                    rawBitmap = rawBitmap,
                )
            }
        }
    }

    /**
     * Applies the manually selected crop corners, re-crops the photo, and returns to preview.
     */
    fun onApplyCrop(newCorners: DocumentCorners) {
        val state = _uiState.value as? ScanUiState.AdjustCrop ?: return
        val rawFile = state.newPage.rawFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val fullPhoto = android.graphics.BitmapFactory.decodeFile(rawFile.absolutePath) ?: return@launch
            val orderedCorners = com.hape.pdfscan.scan.DocumentGeometry.orderCorners(newCorners.toList())
            val newCropped = try {
                com.hape.pdfscan.scan.DocumentDetector.crop(fullPhoto, orderedCorners, maxDimension)
            } finally {
                fullPhoto.recycle()
            }
            if (newCropped == null) {
                withContext(Dispatchers.Main) {
                    _uiState.value = ScanUiState.PagePreview(newPage = state.newPage, pages = state.pages)
                }
                return@launch
            }
            val newTempFile = File(rawFile.parentFile, "scan_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(newTempFile).use { out ->
                newCropped.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            }
            state.newPage.bitmap.recycle()
            state.newPage.jpegFile.delete()
            state.rawBitmap.recycle()

            val updatedPage = state.newPage.copy(
                jpegFile = newTempFile,
                bitmap = newCropped,
                detectedCorners = orderedCorners,
            )
            withContext(Dispatchers.Main) {
                _uiState.value = ScanUiState.PagePreview(newPage = updatedPage, pages = state.pages)
            }
        }
    }

    /**
     * Cancels manual crop adjustment and returns to the preview without changing the crop.
     */
    fun onCancelCrop() {
        val state = _uiState.value as? ScanUiState.AdjustCrop ?: return
        state.rawBitmap.recycle()
        _uiState.value = ScanUiState.PagePreview(newPage = state.newPage, pages = state.pages)
    }

    /**
     * User discards the just-captured page and retakes it (camera restarts, page not added).
     */
    fun onDiscardPage() {
        val state = _uiState.value as? ScanUiState.PagePreview ?: return
        state.newPage.bitmap.recycle()
        state.newPage.jpegFile.delete()
        state.newPage.rawFile?.delete()
        cornerSmoother.reset()
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
                // The cached JPEGs are embedded into the PDF as they are
                if (PdfWriter.write(pages.map { it.jpegFile }, target)) target else false
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
            try { page.rawFile?.delete() } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        autoCaptureController.reset()
        // Clean up everything still in memory
        val state = _uiState.value
        when (state) {
            is ScanUiState.AdjustCrop -> {
                try { state.rawBitmap.recycle() } catch (_: Exception) {}
                cleanupPages(state.pages + state.newPage)
            }
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

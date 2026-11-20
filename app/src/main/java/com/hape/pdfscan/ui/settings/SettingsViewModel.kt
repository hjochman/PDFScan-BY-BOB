package com.hape.pdfscan.ui.settings

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hape.pdfscan.storage.ScanEntry
import com.hape.pdfscan.storage.ScanFolderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---------------------------------------------------------------------------
// UI state
// ---------------------------------------------------------------------------

data class SettingsUiState(
    val currentScanFolder: File? = null,
    val isLoading: Boolean = false,
    /** Non-null while the in-app folder picker is open. */
    val folderPickerState: FolderPickerUiState? = null,
    /** Set after a successful save; screen should show a confirmation. */
    val savedMessage: Boolean = false,
    /** Error string resource to show when a folder is invalid. */
    val folderError: Int? = null,
    /** Current countdown duration in seconds; 0 = auto-capture disabled. */
    val countdownSeconds: Int = com.hape.pdfscan.storage.ScanFolderSettings.DEFAULT_COUNTDOWN_SECONDS,
    /** Longest side of a scanned page in pixels. */
    val maxDimension: Int = ScanFolderSettings.DEFAULT_MAX_DIMENSION,
    /** JPEG quality of a scanned page in percent. */
    val jpegQuality: Int = ScanFolderSettings.DEFAULT_JPEG_QUALITY,
    /** Debug: draw the coloured candidate quads in the scan preview (debug builds only). */
    val showCandidateOverlay: Boolean = ScanFolderSettings.DEFAULT_SHOW_CANDIDATE_OVERLAY,
)

data class FolderPickerUiState(
    val currentDirectory: File,
    val pathStack: List<File> = emptyList(),
    val subFolders: List<ScanEntry> = emptyList(),
    val isLoading: Boolean = false,
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

class SettingsViewModel(
    private val folderSettings: ScanFolderSettings,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState(isLoading = true))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            folderSettings.scanFolder.collect { folder ->
                _uiState.update { it.copy(currentScanFolder = folder, isLoading = false) }
            }
        }
        viewModelScope.launch {
            folderSettings.countdownSeconds.collect { seconds ->
                _uiState.update { it.copy(countdownSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            folderSettings.maxDimension.collect { px ->
                _uiState.update { it.copy(maxDimension = px) }
            }
        }
        viewModelScope.launch {
            folderSettings.jpegQuality.collect { percent ->
                _uiState.update { it.copy(jpegQuality = percent) }
            }
        }
        viewModelScope.launch {
            folderSettings.showCandidateOverlay.collect { show ->
                _uiState.update { it.copy(showCandidateOverlay = show) }
            }
        }
    }

    fun onShowCandidateOverlayChanged(show: Boolean) {
        _uiState.update { it.copy(showCandidateOverlay = show) }
        viewModelScope.launch { folderSettings.setShowCandidateOverlay(show) }
    }

    fun onMaxDimensionChanged(pixels: Int) {
        _uiState.update { it.copy(maxDimension = pixels) }
        viewModelScope.launch { folderSettings.setMaxDimension(pixels) }
    }

    fun onJpegQualityChanged(percent: Int) {
        _uiState.update { it.copy(jpegQuality = percent) }
        viewModelScope.launch { folderSettings.setJpegQuality(percent) }
    }

    // ------------------------------------------------------------------
    // Folder picker
    // ------------------------------------------------------------------

    /** Opens the in-app picker starting at the internal storage root. */
    fun onOpenFolderPicker() {
        val startDir = Environment.getExternalStorageDirectory()
        _uiState.update {
            it.copy(
                folderPickerState = FolderPickerUiState(
                    currentDirectory = startDir,
                    pathStack = emptyList(),
                ),
                folderError = null,
            )
        }
        loadSubFolders(startDir)
    }

    fun onFolderPickerNavigate(folder: File) {
        val current = _uiState.value.folderPickerState ?: return
        val newStack = current.pathStack + current.currentDirectory
        _uiState.update {
            it.copy(
                folderPickerState = current.copy(
                    currentDirectory = folder,
                    pathStack = newStack,
                    isLoading = true,
                ),
            )
        }
        loadSubFolders(folder)
    }

    fun onFolderPickerNavigateUp() {
        val current = _uiState.value.folderPickerState ?: return
        val stack = current.pathStack
        if (stack.isEmpty()) return
        val parent = stack.last()
        val newStack = stack.dropLast(1)
        _uiState.update {
            it.copy(
                folderPickerState = current.copy(
                    currentDirectory = parent,
                    pathStack = newStack,
                    isLoading = true,
                ),
            )
        }
        loadSubFolders(parent)
    }

    fun onFolderPickerSelectHere() {
        val picked = _uiState.value.folderPickerState?.currentDirectory ?: return
        _uiState.update { it.copy(folderPickerState = null) }
        applyFolder(picked)
    }

    fun onFolderPickerDismiss() {
        _uiState.update { it.copy(folderPickerState = null) }
    }

    // ------------------------------------------------------------------
    // Apply / reset
    // ------------------------------------------------------------------

    /** Validates [folder] and persists it if valid. */
    fun applyFolder(folder: File) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { validateFolder(folder) }
            if (error != null) {
                _uiState.update { it.copy(folderError = error) }
                return@launch
            }
            withContext(Dispatchers.IO) {
                folder.mkdirs()
                folderSettings.setScanFolder(folder)
            }
            _uiState.update { it.copy(folderError = null, savedMessage = true) }
        }
    }

    fun onResetFolder() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { folderSettings.resetScanFolder() }
            _uiState.update { it.copy(folderError = null, savedMessage = true) }
        }
    }

    fun onCountdownSecondsChanged(seconds: Int) {
        viewModelScope.launch {
            folderSettings.setCountdownSeconds(seconds)
        }
    }

    fun onSavedMessageConsumed() {
        _uiState.update { it.copy(savedMessage = false) }
    }

    fun onFolderErrorConsumed() {
        _uiState.update { it.copy(folderError = null) }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun loadSubFolders(dir: File) {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                dir.listFiles()
                    ?.filter { it.isDirectory && !it.isHidden }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { ScanEntry(file = it, isDirectory = true, lastModified = it.lastModified(), size = 0L) }
                    ?: emptyList()
            }
            _uiState.update { state ->
                val picker = state.folderPickerState ?: return@update state
                if (picker.currentDirectory == dir) {
                    state.copy(folderPickerState = picker.copy(subFolders = entries, isLoading = false))
                } else state
            }
        }
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    class Factory(private val folderSettings: ScanFolderSettings) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(folderSettings) as T
    }
}

// ---------------------------------------------------------------------------
// Folder validation (pure, callable from tests)
// ---------------------------------------------------------------------------

/**
 * Returns a string resource ID if [folder] is invalid for use as a scan folder, `null` if valid.
 *
 * Rules (per plan):
 * - Must exist or be creatable (mkdirs would succeed) and be a directory.
 * - Must be writable.
 * - Must lie inside the device's primary external storage (internal storage)
 *   as returned by [Environment.getExternalStorageDirectory].
 */
fun validateFolder(folder: File): Int? {
    val internalRoot = Environment.getExternalStorageDirectory().canonicalFile
    val candidate = try {
        folder.canonicalFile
    } catch (_: Exception) {
        return com.hape.pdfscan.R.string.error_settings_folder_invalid
    }
    // Must be inside internal storage
    if (!candidate.path.startsWith(internalRoot.path)) {
        return com.hape.pdfscan.R.string.error_settings_folder_not_internal
    }
    // Must exist and be a directory, or its parent must exist so mkdirs can create it
    val existsAsDir = candidate.exists() && candidate.isDirectory
    val canCreate = !candidate.exists() && (candidate.parentFile?.exists() == true || candidate.mkdirs())
    if (!existsAsDir && !canCreate) {
        return com.hape.pdfscan.R.string.error_settings_folder_invalid
    }
    // Must be writable
    if (candidate.exists() && !candidate.canWrite()) {
        return com.hape.pdfscan.R.string.error_settings_folder_not_writable
    }
    return null
}

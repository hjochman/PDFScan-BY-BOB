package com.hape.pdfscan.ui.main

import androidx.annotation.StringRes
import com.hape.pdfscan.storage.ScanEntry
import java.io.File

/** Mode for file/folder transfer. */
enum class TransferMode {
    COPY,
    MOVE,
}

/** Represents a user message to be shown as a snackbar or alert. */
data class UiMessage(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
)

/** State for the target folder picker dialog / bottom sheet. */
data class FolderPickerState(
    val transferMode: TransferMode,
    val sourceEntry: ScanEntry,
    val currentDirectory: File,
    val pathStack: List<File> = emptyList(),
    val folders: List<ScanEntry> = emptyList(),
    val isLoading: Boolean = false,
)

/** State for a conflict dialog when target file/folder already exists. */
data class ConflictDialogState(
    val transferMode: TransferMode,
    val sourceEntry: ScanEntry,
    val targetDirectory: File,
)

/** UI State for the MainMenuScreen. */
data class MainMenuUiState(
    val hasStoragePermission: Boolean = false,
    val isLoading: Boolean = false,
    val rootDirectory: File? = null,
    val currentDirectory: File? = null,
    val pathStack: List<File> = emptyList(),
    val entries: List<ScanEntry> = emptyList(),
    val uiMessage: UiMessage? = null,
    // Dialog states
    val showCreateFolderDialog: Boolean = false,
    val renamingEntry: ScanEntry? = null,
    val deletingEntry: ScanEntry? = null,
    val folderPickerState: FolderPickerState? = null,
    val conflictDialogState: ConflictDialogState? = null,
) {
    /** Whether we are currently at the root level of the scan directory. */
    val isAtRoot: Boolean
        get() = rootDirectory != null && (currentDirectory == null || currentDirectory == rootDirectory || pathStack.isEmpty())

    /** List of all directories from root to current. */
    val breadcrumbTrail: List<File>
        get() = if (rootDirectory == null || currentDirectory == null) {
            emptyList()
        } else {
            pathStack + currentDirectory
        }
}

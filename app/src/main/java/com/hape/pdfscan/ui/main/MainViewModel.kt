package com.hape.pdfscan.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hape.pdfscan.R
import com.hape.pdfscan.storage.ConflictStrategy
import com.hape.pdfscan.storage.NameValidator
import com.hape.pdfscan.storage.PathGuard
import com.hape.pdfscan.storage.ScanEntry
import com.hape.pdfscan.storage.ScanFileRepository
import com.hape.pdfscan.storage.ScanFolderSettings
import com.hape.pdfscan.storage.StorageError
import com.hape.pdfscan.storage.StorageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class MainViewModel(
    private val folderSettings: ScanFolderSettings,
    private val repositoryFactory: (File) -> ScanFileRepository = { ScanFileRepository(it) },
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainMenuUiState())
    val uiState: StateFlow<MainMenuUiState> = _uiState.asStateFlow()

    private var repository: ScanFileRepository? = null

    init {
        viewModelScope.launch {
            folderSettings.scanFolder.collect { rootFolder ->
                val newRepo = repositoryFactory(rootFolder)
                repository = newRepo
                _uiState.update { current ->
                    current.copy(
                        rootDirectory = rootFolder,
                        currentDirectory = rootFolder,
                        pathStack = emptyList(),
                    )
                }
                if (_uiState.value.hasStoragePermission) {
                    ensureRootAndLoad()
                }
            }
        }
    }

    /** Called on resume or when permission state changes. */
    fun onPermissionStatusChanged(isGranted: Boolean) {
        val wasGranted = _uiState.value.hasStoragePermission
        _uiState.update { it.copy(hasStoragePermission = isGranted) }
        if (isGranted) {
            ensureRootAndLoad()
        } else {
            _uiState.update { it.copy(entries = emptyList(), isLoading = false) }
        }
    }

    /** Ensures the root directory exists and loads the current directory. */
    fun ensureRootAndLoad() {
        val repo = repository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val ensureRes = repo.ensureRoot()) {
                is StorageResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(ensureRes.error)),
                        )
                    }
                }
                is StorageResult.Success -> {
                    val root = ensureRes.value
                    val current = _uiState.value.currentDirectory ?: root
                    loadDirectoryInternal(repo, current, root)
                }
            }
        }
    }

    /** Reloads the current directory. */
    fun refresh() {
        if (!_uiState.value.hasStoragePermission) return
        val repo = repository ?: return
        val current = _uiState.value.currentDirectory ?: _uiState.value.rootDirectory ?: return
        viewModelScope.launch {
            loadDirectoryInternal(repo, current, _uiState.value.rootDirectory ?: current)
        }
    }

    /** Opens a directory in the list. */
    fun openDirectory(directory: File) {
        val repo = repository ?: return
        val current = _uiState.value.currentDirectory
        if (current != null) {
            _uiState.update { it.copy(pathStack = it.pathStack + current) }
        }
        viewModelScope.launch {
            loadDirectoryInternal(repo, directory, _uiState.value.rootDirectory ?: directory)
        }
    }

    /**
     * Navigates one level up.
     * Returns true if navigation occurred, false if already at root.
     */
    fun navigateUp(): Boolean {
        val stack = _uiState.value.pathStack
        if (stack.isEmpty()) return false
        val newCurrent = stack.last()
        val newStack = stack.dropLast(1)
        _uiState.update { it.copy(pathStack = newStack) }
        val repo = repository ?: return true
        viewModelScope.launch {
            loadDirectoryInternal(repo, newCurrent, _uiState.value.rootDirectory ?: newCurrent)
        }
        return true
    }

    /** Navigates directly to an ancestor directory in the breadcrumb trail. */
    fun navigateToBreadcrumb(target: File) {
        val current = _uiState.value.currentDirectory
        if (target == current) return
        val stack = _uiState.value.pathStack
        val index = stack.indexOf(target)
        if (index >= 0) {
            val newStack = stack.take(index)
            _uiState.update { it.copy(pathStack = newStack) }
            val repo = repository ?: return
            viewModelScope.launch {
                loadDirectoryInternal(repo, target, _uiState.value.rootDirectory ?: target)
            }
        } else if (target == _uiState.value.rootDirectory) {
            _uiState.update { it.copy(pathStack = emptyList()) }
            val repo = repository ?: return
            viewModelScope.launch {
                loadDirectoryInternal(repo, target, target)
            }
        }
    }

    private suspend fun loadDirectoryInternal(repo: ScanFileRepository, directory: File, root: File) {
        _uiState.update { it.copy(isLoading = true) }
        when (val result = repo.list(directory)) {
            is StorageResult.Success -> {
                _uiState.update {
                    it.copy(
                        currentDirectory = directory,
                        entries = result.value,
                        isLoading = false,
                    )
                }
            }
            is StorageResult.Failure -> {
                // If directory not found or outside root, reset to root
                val fallbackToRoot = result.error is StorageError.NotFound || result.error is StorageError.OutsideRoot
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentDirectory = if (fallbackToRoot) root else it.currentDirectory,
                        pathStack = if (fallbackToRoot) emptyList() else it.pathStack,
                        uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(result.error)),
                    )
                }
                if (fallbackToRoot) {
                    val rootList = repo.list(root)
                    if (rootList is StorageResult.Success) {
                        _uiState.update { it.copy(entries = rootList.value) }
                    }
                }
            }
        }
    }

    // ---- Dialog and Action Handlers -------------------------------------------------------------

    fun openCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = true) }
    }

    fun dismissCreateFolderDialog() {
        _uiState.update { it.copy(showCreateFolderDialog = false) }
    }

    fun createFolder(name: String) {
        val repo = repository ?: return
        val current = _uiState.value.currentDirectory ?: _uiState.value.rootDirectory ?: return
        viewModelScope.launch {
            val problem = NameValidator.validate(name)
            if (problem != null) {
                _uiState.update {
                    it.copy(uiMessage = UiMessage(StorageErrorMapper.mapNameProblem(problem)))
                }
                return@launch
            }
            when (val res = repo.createFolder(current, name)) {
                is StorageResult.Success -> {
                    _uiState.update {
                        it.copy(
                            showCreateFolderDialog = false,
                            uiMessage = UiMessage(R.string.msg_folder_created),
                        )
                    }
                    refresh()
                }
                is StorageResult.Failure -> {
                    _uiState.update {
                        it.copy(uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(res.error)))
                    }
                }
            }
        }
    }

    fun openRenameDialog(entry: ScanEntry) {
        _uiState.update { it.copy(renamingEntry = entry) }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renamingEntry = null) }
    }

    fun renameEntry(entry: ScanEntry, rawNewName: String) {
        val repo = repository ?: return
        viewModelScope.launch {
            val fullName = if (entry.isDirectory) {
                rawNewName
            } else {
                if (rawNewName.endsWith(".pdf", ignoreCase = true)) rawNewName else "$rawNewName.pdf"
            }
            val problem = NameValidator.validate(fullName)
            if (problem != null) {
                _uiState.update {
                    it.copy(uiMessage = UiMessage(StorageErrorMapper.mapNameProblem(problem)))
                }
                return@launch
            }
            when (val res = repo.rename(entry.file, fullName)) {
                is StorageResult.Success -> {
                    _uiState.update {
                        it.copy(
                            renamingEntry = null,
                            uiMessage = UiMessage(R.string.msg_renamed),
                        )
                    }
                    refresh()
                }
                is StorageResult.Failure -> {
                    _uiState.update {
                        it.copy(uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(res.error)))
                    }
                }
            }
        }
    }

    fun openDeleteDialog(entry: ScanEntry) {
        _uiState.update { it.copy(deletingEntry = entry) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(deletingEntry = null) }
    }

    fun confirmDelete(entry: ScanEntry) {
        val repo = repository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(deletingEntry = null, isLoading = true) }
            when (val res = repo.delete(entry.file)) {
                is StorageResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            uiMessage = UiMessage(R.string.msg_deleted),
                        )
                    }
                    refresh()
                }
                is StorageResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(res.error)),
                        )
                    }
                    refresh()
                }
            }
        }
    }

    // ---- Copy & Move (Folder Picker & Conflicts) ------------------------------------------------

    fun startTransfer(entry: ScanEntry, mode: TransferMode) {
        val root = _uiState.value.rootDirectory ?: return
        val repo = repository ?: return
        viewModelScope.launch {
            val foldersRes = repo.list(root)
            val folders = (foldersRes as? StorageResult.Success)?.value?.filter { it.isDirectory } ?: emptyList()
            _uiState.update {
                it.copy(
                    folderPickerState = FolderPickerState(
                        transferMode = mode,
                        sourceEntry = entry,
                        currentDirectory = root,
                        pathStack = emptyList(),
                        folders = folders,
                    ),
                )
            }
        }
    }

    fun dismissFolderPicker() {
        _uiState.update { it.copy(folderPickerState = null) }
    }

    fun pickerOpenDirectory(directory: File) {
        val state = _uiState.value.folderPickerState ?: return
        val repo = repository ?: return
        viewModelScope.launch {
            val foldersRes = repo.list(directory)
            val folders = (foldersRes as? StorageResult.Success)?.value?.filter { it.isDirectory } ?: emptyList()
            _uiState.update { current ->
                val currPicker = current.folderPickerState ?: return@update current
                current.copy(
                    folderPickerState = currPicker.copy(
                        currentDirectory = directory,
                        pathStack = currPicker.pathStack + currPicker.currentDirectory,
                        folders = folders,
                    ),
                )
            }
        }
    }

    fun pickerNavigateUp() {
        val state = _uiState.value.folderPickerState ?: return
        if (state.pathStack.isEmpty()) return
        val target = state.pathStack.last()
        val newStack = state.pathStack.dropLast(1)
        val repo = repository ?: return
        viewModelScope.launch {
            val foldersRes = repo.list(target)
            val folders = (foldersRes as? StorageResult.Success)?.value?.filter { it.isDirectory } ?: emptyList()
            _uiState.update { current ->
                val currPicker = current.folderPickerState ?: return@update current
                current.copy(
                    folderPickerState = currPicker.copy(
                        currentDirectory = target,
                        pathStack = newStack,
                        folders = folders,
                    ),
                )
            }
        }
    }

    /** Returns true if [candidate] is locked (the folder itself or one of its descendants). */
    fun isFolderLockedInPicker(candidate: File, source: ScanEntry): Boolean {
        if (!source.isDirectory) return false
        val sourceCanonical = safeCanonical(source.file) ?: return false
        val candidateCanonical = safeCanonical(candidate) ?: return false
        return PathGuard.isSameOrDescendant(candidateCanonical, sourceCanonical)
    }

    private fun safeCanonical(file: File): File? = try {
        file.canonicalFile
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    fun executeTransfer(
        source: ScanEntry,
        targetDirectory: File,
        mode: TransferMode,
        conflict: ConflictStrategy = ConflictStrategy.FAIL,
    ) {
        val repo = repository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = when (mode) {
                TransferMode.COPY -> repo.copy(source.file, targetDirectory, conflict)
                TransferMode.MOVE -> repo.move(source.file, targetDirectory, conflict)
            }
            when (result) {
                is StorageResult.Success -> {
                    val msgRes = if (mode == TransferMode.COPY) R.string.msg_copied else R.string.msg_moved
                    _uiState.update {
                        it.copy(
                            folderPickerState = null,
                            conflictDialogState = null,
                            isLoading = false,
                            uiMessage = UiMessage(msgRes),
                        )
                    }
                    refresh()
                }
                is StorageResult.Failure -> {
                    if (result.error is StorageError.AlreadyExists && conflict == ConflictStrategy.FAIL) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                folderPickerState = null,
                                conflictDialogState = ConflictDialogState(
                                    transferMode = mode,
                                    sourceEntry = source,
                                    targetDirectory = targetDirectory,
                                ),
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                folderPickerState = null,
                                conflictDialogState = null,
                                uiMessage = UiMessage(StorageErrorMapper.mapToStringRes(result.error)),
                            )
                        }
                        if (result.error is StorageError.PartiallyDone) {
                            refresh()
                        }
                    }
                }
            }
        }
    }

    fun dismissConflictDialog() {
        _uiState.update { it.copy(conflictDialogState = null) }
    }

    fun resolveConflictKeepBoth() {
        val conflict = _uiState.value.conflictDialogState ?: return
        executeTransfer(
            source = conflict.sourceEntry,
            targetDirectory = conflict.targetDirectory,
            mode = conflict.transferMode,
            conflict = ConflictStrategy.KEEP_BOTH,
        )
    }

    fun clearUiMessage() {
        _uiState.update { it.copy(uiMessage = null) }
    }

    companion object {
        fun provideFactory(
            folderSettings: ScanFolderSettings,
            repositoryFactory: (File) -> ScanFileRepository = { ScanFileRepository(it) },
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(folderSettings, repositoryFactory) as T
            }
        }
    }
}

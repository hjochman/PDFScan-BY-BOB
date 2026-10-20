package com.hape.pdfscan.ui.main

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.hape.pdfscan.R
import com.hape.pdfscan.storage.ScanEntry
import com.hape.pdfscan.storage.ScanFileRepository
import com.hape.pdfscan.storage.ScanFolderSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testDispatcher)

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootFolder: File
    private lateinit var folderSettings: ScanFolderSettings
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        rootFolder = tempFolder.newFolder("Scans")
        val dataStoreFile = tempFolder.newFile("settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(testDispatcher),
            produceFile = { dataStoreFile },
        )
        folderSettings = ScanFolderSettings(
            dataStore = dataStore,
            defaultFolder = { rootFolder },
        )
        viewModel = MainViewModel(
            folderSettings = folderSettings,
            repositoryFactory = { ScanFileRepository(it, ioDispatcher = testDispatcher, logger = {}) },
        )
    }

    @Test
    fun initialPermissionStateIsFalseAndEntriesEmpty() {
        assertFalse(viewModel.uiState.value.hasStoragePermission)
        assertTrue(viewModel.uiState.value.entries.isEmpty())
    }

    @Test
    fun grantingPermissionLoadsRootEntries() = runTest(testDispatcher) {
        val samplePdf = File(rootFolder, "doc1.pdf").apply { writeText("dummy pdf") }
        val sampleFolder = File(rootFolder, "SubFolder").apply { mkdir() }

        viewModel.onPermissionStatusChanged(true)

        val state = viewModel.uiState.value
        assertTrue(state.hasStoragePermission)
        assertEquals(2, state.entries.size)
        // Folders first, then pdfs
        assertTrue(state.entries[0].isDirectory)
        assertEquals("SubFolder", state.entries[0].name)
        assertFalse(state.entries[1].isDirectory)
        assertEquals("doc1.pdf", state.entries[1].name)
        assertTrue(state.isAtRoot)
    }

    @Test
    fun folderNavigationAndNavigateUp() = runTest(testDispatcher) {
        val sub1 = File(rootFolder, "FolderA").apply { mkdir() }
        File(sub1, "nested.pdf").apply { writeText("nested") }

        viewModel.onPermissionStatusChanged(true)
        assertTrue(viewModel.uiState.value.isAtRoot)

        viewModel.openDirectory(sub1)
        var state = viewModel.uiState.value
        assertFalse(state.isAtRoot)
        assertEquals(sub1, state.currentDirectory)
        assertEquals(listOf(rootFolder), state.pathStack)
        assertEquals(1, state.entries.size)
        assertEquals("nested.pdf", state.entries[0].name)

        val handled = viewModel.navigateUp()
        assertTrue(handled)
        state = viewModel.uiState.value
        assertTrue(state.isAtRoot)
        assertEquals(rootFolder, state.currentDirectory)
        assertTrue(state.pathStack.isEmpty())

        val handledAtRoot = viewModel.navigateUp()
        assertFalse(handledAtRoot)
    }

    @Test
    fun breadcrumbNavigation() = runTest(testDispatcher) {
        val sub1 = File(rootFolder, "Level1").apply { mkdir() }
        val sub2 = File(sub1, "Level2").apply { mkdir() }
        File(sub2, "deep.pdf").apply { writeText("deep") }

        viewModel.onPermissionStatusChanged(true)
        viewModel.openDirectory(sub1)
        viewModel.openDirectory(sub2)

        var state = viewModel.uiState.value
        assertEquals(listOf(rootFolder, sub1, sub2), state.breadcrumbTrail)

        // Jump back to Level1 via breadcrumb
        viewModel.navigateToBreadcrumb(sub1)
        state = viewModel.uiState.value
        assertEquals(sub1, state.currentDirectory)
        assertEquals(listOf(rootFolder), state.pathStack)

        // Jump back to root
        viewModel.navigateToBreadcrumb(rootFolder)
        state = viewModel.uiState.value
        assertEquals(rootFolder, state.currentDirectory)
        assertTrue(state.isAtRoot)
    }

    @Test
    fun createFolderValidationAndSuccess() = runTest(testDispatcher) {
        viewModel.onPermissionStatusChanged(true)

        // Invalid name
        viewModel.createFolder("invalid/name")
        assertNotNull(viewModel.uiState.value.uiMessage)
        assertEquals(R.string.error_name_forbidden_char, viewModel.uiState.value.uiMessage?.messageRes)

        // Valid name
        viewModel.createFolder("NewValidFolder")
        assertEquals(R.string.msg_folder_created, viewModel.uiState.value.uiMessage?.messageRes)
        assertTrue(File(rootFolder, "NewValidFolder").isDirectory)
        assertTrue(viewModel.uiState.value.entries.any { it.name == "NewValidFolder" })
    }

    @Test
    fun renameEntryForPdfAndFolder() = runTest(testDispatcher) {
        val file = File(rootFolder, "OldName.pdf").apply { writeText("content") }
        val folder = File(rootFolder, "OldFolder").apply { mkdir() }
        viewModel.onPermissionStatusChanged(true)

        val pdfEntry = ScanEntry(file, false, file.lastModified(), file.length())
        viewModel.renameEntry(pdfEntry, "NewName")
        assertTrue(File(rootFolder, "NewName.pdf").exists())
        assertFalse(file.exists())

        val folderEntry = ScanEntry(folder, true, folder.lastModified(), 0L)
        viewModel.renameEntry(folderEntry, "NewFolder")
        assertTrue(File(rootFolder, "NewFolder").isDirectory)
        assertFalse(folder.exists())
    }

    @Test
    fun deleteEntryRemovesFileOrFolder() = runTest(testDispatcher) {
        val file = File(rootFolder, "ToDelete.pdf").apply { writeText("content") }
        viewModel.onPermissionStatusChanged(true)

        val entry = ScanEntry(file, false, file.lastModified(), file.length())
        viewModel.confirmDelete(entry)

        assertFalse(file.exists())
        assertEquals(R.string.msg_deleted, viewModel.uiState.value.uiMessage?.messageRes)
    }

    @Test
    fun folderPickerLockedCheck() {
        val folderA = File(rootFolder, "FolderA").apply { mkdir() }
        val subA = File(folderA, "SubA").apply { mkdir() }
        val folderB = File(rootFolder, "FolderB").apply { mkdir() }
        val file = File(rootFolder, "doc.pdf").apply { writeText("pdf") }

        val folderEntryA = ScanEntry(folderA, true, folderA.lastModified(), 0L)
        val fileEntry = ScanEntry(file, false, file.lastModified(), file.length())

        // When source is FolderA: FolderA and SubA are locked; FolderB and root are not locked
        assertTrue(viewModel.isFolderLockedInPicker(folderA, folderEntryA))
        assertTrue(viewModel.isFolderLockedInPicker(subA, folderEntryA))
        assertFalse(viewModel.isFolderLockedInPicker(folderB, folderEntryA))
        assertFalse(viewModel.isFolderLockedInPicker(rootFolder, folderEntryA))

        // When source is a PDF file: nothing is locked
        assertFalse(viewModel.isFolderLockedInPicker(folderA, fileEntry))
        assertFalse(viewModel.isFolderLockedInPicker(subA, fileEntry))
    }

    @Test
    fun transferCopyAndConflictResolution() = runTest(testDispatcher) {
        val targetDir = File(rootFolder, "Target").apply { mkdir() }
        val sourceFile = File(rootFolder, "Report.pdf").apply { writeText("pdf 1") }
        // Create an existing file at target to trigger conflict
        File(targetDir, "Report.pdf").apply { writeText("existing") }

        viewModel.onPermissionStatusChanged(true)
        val sourceEntry = ScanEntry(sourceFile, false, sourceFile.lastModified(), sourceFile.length())

        // Initial copy will fail with AlreadyExists and open conflict dialog
        viewModel.executeTransfer(sourceEntry, targetDir, TransferMode.COPY)
        val conflictState = viewModel.uiState.value.conflictDialogState
        assertNotNull(conflictState)
        assertEquals(sourceEntry, conflictState?.sourceEntry)
        assertEquals(targetDir, conflictState?.targetDirectory)

        // Resolve conflict with KEEP_BOTH
        viewModel.resolveConflictKeepBoth()
        assertNull(viewModel.uiState.value.conflictDialogState)
        assertTrue(File(targetDir, "Report (2).pdf").exists())
        assertEquals(R.string.msg_copied, viewModel.uiState.value.uiMessage?.messageRes)
    }

    @Test
    fun transferMoveMovesFile() = runTest(testDispatcher) {
        val targetDir = File(rootFolder, "Target").apply { mkdir() }
        val sourceFile = File(rootFolder, "ToMove.pdf").apply { writeText("pdf move") }

        viewModel.onPermissionStatusChanged(true)
        val sourceEntry = ScanEntry(sourceFile, false, sourceFile.lastModified(), sourceFile.length())

        viewModel.executeTransfer(sourceEntry, targetDir, TransferMode.MOVE)
        assertFalse(sourceFile.exists())
        assertTrue(File(targetDir, "ToMove.pdf").exists())
        assertEquals(R.string.msg_moved, viewModel.uiState.value.uiMessage?.messageRes)
    }
}

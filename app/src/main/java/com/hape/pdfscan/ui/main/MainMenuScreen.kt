package com.hape.pdfscan.ui.main

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hape.pdfscan.R
import com.hape.pdfscan.storage.PermissionRequest
import com.hape.pdfscan.storage.ScanEntry
import com.hape.pdfscan.storage.ScanFolderSettings
import com.hape.pdfscan.storage.StoragePermission
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenuScreen(
    onNavigateToScan: (File?) -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPdfViewer: (File) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel(
        factory = MainViewModel.provideFactory(ScanFolderSettings(LocalContext.current.applicationContext)),
    ),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Intercept system back when in subfolder
    BackHandler(enabled = !uiState.isAtRoot) {
        viewModel.navigateUp()
    }

    // Permission launcher for runtime permissions (API 29)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionStatusChanged(StoragePermission.isGranted(context))
    }

    // Check storage permission on start and on resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onPermissionStatusChanged(StoragePermission.isGranted(context))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Show Snackbar on uiMessage changes
    uiState.uiMessage?.let { msg ->
        val text = stringResource(msg.messageRes, *msg.formatArgs.toTypedArray())
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(text)
            viewModel.clearUiMessage()
        }
    }

    var showOverflowMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (!uiState.isAtRoot) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_up),
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.hasStoragePermission) {
                        IconButton(onClick = { viewModel.openCreateFolderDialog() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_create_new_folder),
                                contentDescription = stringResource(R.string.action_create_folder),
                            )
                        }
                    }
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_more_options),
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.title_settings)) },
                            onClick = {
                                showOverflowMenu = false
                                onNavigateToSettings()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.title_about)) },
                            onClick = {
                                showOverflowMenu = false
                                onNavigateToAbout()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.hasStoragePermission) {
                FloatingActionButton(
                    onClick = { onNavigateToScan(uiState.currentDirectory) },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = stringResource(R.string.action_scan),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (!uiState.hasStoragePermission) {
                PermissionMissingContent(
                    onRequestPermission = {
                        requestStoragePermission(context, permissionLauncher::launch)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Breadcrumbs bar
                if (uiState.breadcrumbTrail.isNotEmpty()) {
                    BreadcrumbBar(
                        trail = uiState.breadcrumbTrail,
                        rootDirectory = uiState.rootDirectory,
                        onBreadcrumbClicked = { viewModel.navigateToBreadcrumb(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    HorizontalDivider()
                }

                if (uiState.isLoading && uiState.entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.entries.isEmpty()) {
                    EmptyFolderContent(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(uiState.entries, key = { it.file.absolutePath }) { entry ->
                            ScanEntryRow(
                                entry = entry,
                                onOpenFolder = { viewModel.openDirectory(entry.file) },
                                onOpenFile = { onNavigateToPdfViewer(entry.file) },
                                onRename = { viewModel.openRenameDialog(entry) },
                                onCopy = { viewModel.startTransfer(entry, TransferMode.COPY) },
                                onMove = { viewModel.startTransfer(entry, TransferMode.MOVE) },
                                onDelete = { viewModel.openDeleteDialog(entry) },
                                onShare = { sharePdfFile(context, entry.file) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { viewModel.dismissCreateFolderDialog() },
            onConfirm = { viewModel.createFolder(it) },
        )
    }

    uiState.renamingEntry?.let { entry ->
        RenameDialog(
            entry = entry,
            onDismiss = { viewModel.dismissRenameDialog() },
            onConfirm = { target, newName -> viewModel.renameEntry(target, newName) },
        )
    }

    uiState.deletingEntry?.let { entry ->
        DeleteConfirmDialog(
            entry = entry,
            onDismiss = { viewModel.dismissDeleteDialog() },
            onConfirm = { viewModel.confirmDelete(it) },
        )
    }

    uiState.folderPickerState?.let { pickerState ->
        FolderPickerDialog(
            state = pickerState,
            onDismiss = { viewModel.dismissFolderPicker() },
            onOpenDirectory = { viewModel.pickerOpenDirectory(it) },
            onNavigateUp = { viewModel.pickerNavigateUp() },
            onSelectHere = { targetDir ->
                viewModel.executeTransfer(
                    source = pickerState.sourceEntry,
                    targetDirectory = targetDir,
                    mode = pickerState.transferMode,
                )
            },
            isFolderLocked = { candidate, source ->
                viewModel.isFolderLockedInPicker(candidate, source)
            },
        )
    }

    uiState.conflictDialogState?.let {
        ConflictResolutionDialog(
            state = it,
            onDismiss = { viewModel.dismissConflictDialog() },
            onKeepBoth = { viewModel.resolveConflictKeepBoth() },
        )
    }
}

@Composable
private fun BreadcrumbBar(
    trail: List<File>,
    rootDirectory: File?,
    onBreadcrumbClicked: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier.horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        trail.forEachIndexed { index, file ->
            val label = if (file == rootDirectory) {
                stringResource(R.string.default_folder_name)
            } else {
                file.name
            }
            val isLast = index == trail.lastIndex

            SuggestionChip(
                onClick = { onBreadcrumbClicked(file) },
                label = {
                    Text(
                        text = label,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            if (!isLast) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun ScanEntryRow(
    entry: ScanEntry,
    onOpenFolder: () -> Unit,
    onOpenFile: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val formattedDate = remember(entry.lastModified) {
        if (entry.lastModified > 0L) dateFormat.format(Date(entry.lastModified)) else ""
    }
    val subtitle = if (entry.isDirectory) {
        formattedDate
    } else {
        val sizeStr = Formatter.formatFileSize(context, entry.size)
        if (formattedDate.isNotEmpty()) "$formattedDate • $sizeStr" else sizeStr
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = if (entry.isDirectory) onOpenFolder else onOpenFile)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (entry.isDirectory) R.drawable.ic_folder else R.drawable.ic_pdf_file,
            ),
            contentDescription = stringResource(
                if (entry.isDirectory) R.string.cd_folder_icon else R.string.cd_pdf_icon,
            ),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondary
            },
            modifier = Modifier.size(36.dp),
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more_options),
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                if (!entry.isDirectory) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onShare()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_rename)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onCopy()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_move)) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_drive_file_move),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onMove()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyFolderContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.empty_folder_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.empty_folder_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PermissionMissingContent(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_storage_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_storage_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequestPermission) {
                Text(text = stringResource(R.string.permission_storage_button))
            }
        }
    }
}

private fun requestStoragePermission(
    context: Context,
    runtimeLauncher: (Array<String>) -> Unit,
) {
    when (val request = StoragePermission.requestFor(context)) {
        is PermissionRequest.NotNeeded -> { /* already granted */ }
        is PermissionRequest.RuntimePermissions -> {
            runtimeLauncher(request.permissions)
        }
        is PermissionRequest.AllFilesAccessSettings -> {
            try {
                context.startActivity(request.intent)
            } catch (_: ActivityNotFoundException) {
                try {
                    context.startActivity(request.fallbackIntent)
                } catch (_: Exception) {
                    // Safe fallback ignore
                }
            } catch (_: Exception) {
                // Safe fallback ignore
            }
        }
    }
}

private fun openPdfFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No PDF viewer installed – fall back to share sheet so the user can pick an app
        sharePdfFile(context, file)
    } catch (_: Exception) {
        // Safe fallback
    }
}

private fun sharePdfFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (_: Exception) {
        // Safe fallback
    }
}

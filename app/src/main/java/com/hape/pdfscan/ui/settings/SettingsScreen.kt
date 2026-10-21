package com.hape.pdfscan.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hape.pdfscan.R
import com.hape.pdfscan.storage.ScanEntry
import com.hape.pdfscan.storage.ScanFolderSettings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(ScanFolderSettings(context)),
    )
    val uiState by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show confirmation snackbar
    val savedMsg = stringResource(R.string.settings_folder_saved)
    LaunchedEffect(uiState.savedMessage) {
        if (uiState.savedMessage) {
            snackbarHostState.showSnackbar(savedMsg)
            vm.onSavedMessageConsumed()
        }
    }

    // Show error snackbar
    val folderErrorRes = uiState.folderError
    val folderErrorMsg = folderErrorRes?.let { stringResource(it) }
    LaunchedEffect(folderErrorRes) {
        if (folderErrorMsg != null) {
            snackbarHostState.showSnackbar(folderErrorMsg)
            vm.onFolderErrorConsumed()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // Section: Scan folder
            Text(
                text = stringResource(R.string.settings_section_scan_folder),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val folder = uiState.currentScanFolder
            Text(
                text = stringResource(R.string.settings_current_scan_folder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = folder?.absolutePath ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.onOpenFolderPicker() }) {
                    Text(stringResource(R.string.settings_choose_folder))
                }
                OutlinedButton(onClick = { vm.onResetFolder() }) {
                    Text(stringResource(R.string.settings_reset_folder))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Auto-capture countdown
            Text(
                text = stringResource(R.string.settings_section_countdown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            val seconds = uiState.countdownSeconds
            Text(
                text = if (seconds == 0)
                    stringResource(R.string.settings_countdown_off)
                else
                    stringResource(R.string.settings_countdown_seconds, seconds),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = seconds.toFloat(),
                onValueChange = { vm.onCountdownSecondsChanged(it.toInt()) },
                valueRange = 0f..com.hape.pdfscan.storage.ScanFolderSettings.MAX_COUNTDOWN_SECONDS.toFloat(),
                steps = com.hape.pdfscan.storage.ScanFolderSettings.MAX_COUNTDOWN_SECONDS - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_countdown_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Section: PDF size (resolution + JPEG quality)
            Text(
                text = stringResource(R.string.settings_section_pdf_size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_resolution_value, uiState.maxDimension),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = uiState.maxDimension.toFloat(),
                onValueChange = { vm.onMaxDimensionChanged(it.roundToStep(ScanFolderSettings.MAX_DIMENSION_STEP)) },
                valueRange = ScanFolderSettings.MIN_MAX_DIMENSION.toFloat()..ScanFolderSettings.MAX_MAX_DIMENSION.toFloat(),
                steps = (ScanFolderSettings.MAX_MAX_DIMENSION - ScanFolderSettings.MIN_MAX_DIMENSION) /
                    ScanFolderSettings.MAX_DIMENSION_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.settings_quality_value, uiState.jpegQuality),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Slider(
                value = uiState.jpegQuality.toFloat(),
                onValueChange = { vm.onJpegQualityChanged(it.roundToStep(ScanFolderSettings.JPEG_QUALITY_STEP)) },
                valueRange = ScanFolderSettings.MIN_JPEG_QUALITY.toFloat()..ScanFolderSettings.MAX_JPEG_QUALITY.toFloat(),
                steps = (ScanFolderSettings.MAX_JPEG_QUALITY - ScanFolderSettings.MIN_JPEG_QUALITY) /
                    ScanFolderSettings.JPEG_QUALITY_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_pdf_size_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Folder picker dialog
        val pickerState = uiState.folderPickerState
        if (pickerState != null) {
            SettingsFolderPickerDialog(
                state = pickerState,
                onDismiss = { vm.onFolderPickerDismiss() },
                onNavigate = { vm.onFolderPickerNavigate(it) },
                onNavigateUp = { vm.onFolderPickerNavigateUp() },
                onSelectHere = { vm.onFolderPickerSelectHere() },
            )
        }
    }
}

/** Rounds a slider value to the nearest multiple of [step]. */
private fun Float.roundToStep(step: Int): Int = (Math.round(this / step) * step)

// =============================================================================
// Folder picker dialog (settings-specific, no copy/move logic)
// =============================================================================

@Composable
private fun SettingsFolderPickerDialog(
    state: FolderPickerUiState,
    onDismiss: () -> Unit,
    onNavigate: (File) -> Unit,
    onNavigateUp: () -> Unit,
    onSelectHere: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_choose_folder),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Current directory bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.pathStack.isNotEmpty()) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_up),
                            )
                        }
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.cd_folder_icon),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(24.dp),
                        )
                    }
                    Text(
                        text = state.currentDirectory.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Subfolder list
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                ) {
                    if (state.subFolders.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.settings_no_subfolders),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(state.subFolders, key = { it.file.absolutePath }) { entry ->
                                FolderRow(entry = entry, onClick = { onNavigate(entry.file) })
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSelectHere) {
                        Text(stringResource(R.string.action_select_here))
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(entry: ScanEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_folder),
            contentDescription = stringResource(R.string.cd_folder_icon),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

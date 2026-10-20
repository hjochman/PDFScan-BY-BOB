package com.hape.pdfscan.ui.scan

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hape.pdfscan.R
import com.hape.pdfscan.scan.AutoCaptureState
import com.hape.pdfscan.scan.DetectionResult
import com.hape.pdfscan.scan.DocumentGeometry
import com.hape.pdfscan.scan.FloatPoint
import com.hape.pdfscan.storage.ScanFileRepository
import com.hape.pdfscan.storage.ScanFolderSettings
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    targetDirectory: File?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: ScanViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ScanViewModel(ScanFolderSettings(context.applicationContext)) as T
        }
    )
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    // Observe save completion and navigate back when done
    val savedPdf by viewModel.savedPdf.collectAsState()
    LaunchedEffect(savedPdf) {
        if (savedPdf != null) onNavigateBack()
    }

    // Repository for duplicate check (read-only use)
    val repository = remember(targetDirectory) {
        if (targetDirectory != null) ScanFileRepository(targetDirectory) else null
    }

    // Camera controller; created once per composition of this screen
    val controller = remember { ScanCameraController(context, viewModel) }

    // Wire up the auto-capture callback
    SideEffect {
        viewModel.onAutoCaptureFire = { controller.capture(context.cacheDir) }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.onAutoCaptureFire = null
            controller.release()
        }
    }

    // The camera is not needed while a captured image or naming dialog is shown
    LaunchedEffect(uiState) {
        if (uiState !is ScanUiState.Scanning) controller.unbind()
    }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(
            activity, android.Manifest.permission.CAMERA
        )
        viewModel.onPermissionResult(
            granted = granted,
            isPermanentlyDenied = !granted && !canAskAgain,
        )
    }

    // Check permission once on first composition
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            viewModel.onPermissionGranted()
        } else {
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.title_scan)) },
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
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val state = uiState) {
                is ScanUiState.PermissionDenied -> {
                    CameraPermissionDeniedContent(
                        isPermanentlyDenied = state.isPermanentlyDenied,
                        onRequestPermission = {
                            permissionLauncher.launch(android.Manifest.permission.CAMERA)
                        },
                        onOpenSettings = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                    )
                }

                is ScanUiState.Scanning -> {
                    val diagnostics by viewModel.diagnostics.collectAsState()
                    val autoCaptureState by viewModel.autoCaptureState.collectAsState()
                    val isDebuggable = (context.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    CameraPreviewContent(
                        latestDetection = state.latestDetection,
                        autoCaptureState = autoCaptureState,
                        diagnostics = if (isDebuggable) diagnostics else null,
                        onCapture = { controller.capture(context.cacheDir) },
                        onBind = { previewView ->
                            controller.bindCamera(previewView, lifecycleOwner)
                        },
                    )
                }

                is ScanUiState.PagePreview -> {
                    PagePreviewContent(
                        page = state.newPage,
                        pageNumber = state.pages.size + 1,
                        onDiscard = { viewModel.onDiscardPage() },
                        onAddAnother = { viewModel.onAddAnotherPage() },
                        onFinish = { viewModel.onFinishScanning() },
                    )
                }

                is ScanUiState.Naming -> {
                    NamingContent(
                        pageCount = state.pages.size,
                        nameError = state.nameError?.let { stringResource(it) },
                        isSaving = state.isSaving,
                        onSave = { name ->
                            val dir = targetDirectory ?: context.filesDir
                            if (repository != null) {
                                viewModel.onSavePdf(name, dir, repository)
                            } else {
                                viewModel.onSavePdf(name, dir, ScanFileRepository(dir))
                            }
                        },
                        onCancel = { viewModel.onCancelNaming() },
                    )
                }

                is ScanUiState.Error -> {
                    ErrorContent(
                        message = stringResource(state.messageRes),
                        onNavigateBack = onNavigateBack,
                    )
                }
            }
        }
    }
}

// =============================================================================
// Camera preview + overlay
// =============================================================================

@Composable
private fun CameraPreviewContent(
    latestDetection: DetectionResult?,
    autoCaptureState: AutoCaptureState,
    diagnostics: String?,
    onCapture: () -> Unit,
    onBind: (androidx.camera.view.PreviewView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                androidx.camera.view.PreviewView(ctx).apply {
                    scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
                    onBind(this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { previewSize = it },
        )

        if (latestDetection != null && !latestDetection.isFallback && previewSize != IntSize.Zero) {
            DocumentOverlay(
                detection = latestDetection,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            )
        }

        if (!diagnostics.isNullOrEmpty()) {
            Text(
                text = diagnostics,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(Color(0x99000000))
                    .padding(4.dp),
            )
        }

        if (autoCaptureState is AutoCaptureState.Stable) {
            CountdownDisplay(
                remainingSeconds = autoCaptureState.remainingSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
            )
        }

        CaptureButton(
            onClick = onCapture,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun CountdownDisplay(
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color(0xCC000000), shape = CircleShape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = remainingSeconds.toString(),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DocumentOverlay(
    detection: DetectionResult,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        fun mapPoint(fp: FloatPoint): Offset {
            val p = DocumentGeometry.mapFillCenter(
                point = fp,
                imageWidth = detection.imageWidth,
                imageHeight = detection.imageHeight,
                viewWidth = size.width,
                viewHeight = size.height,
            )
            return Offset(p.x, p.y)
        }

        val corners = detection.corners
        val path = Path().apply {
            val tl = mapPoint(corners.topLeft)
            val tr = mapPoint(corners.topRight)
            val br = mapPoint(corners.bottomRight)
            val bl = mapPoint(corners.bottomLeft)
            moveTo(tl.x, tl.y)
            lineTo(tr.x, tr.y)
            lineTo(br.x, br.y)
            lineTo(bl.x, bl.y)
            close()
        }
        drawPath(path, color = Color(0x3300AAFF))
        drawPath(path, color = Color(0xFF00AAFF), style = Stroke(width = 4f))
    }
}

@Composable
private fun CaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(72.dp),
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_camera),
            contentDescription = stringResource(R.string.action_capture_photo),
            tint = Color.White,
            modifier = Modifier.size(48.dp),
        )
    }
}

// =============================================================================
// Page preview (after capture)
// =============================================================================

@Composable
private fun PagePreviewContent(
    page: ScannedPage,
    pageNumber: Int,
    onDiscard: () -> Unit,
    onAddAnother: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.scan_page_number, pageNumber),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = stringResource(R.string.cd_captured_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDiscard,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_discard))
            }
            OutlinedButton(
                onClick = onAddAnother,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_add_page))
            }
            Button(
                onClick = onFinish,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_finish_scan))
            }
        }
    }
}

// =============================================================================
// Naming dialog (inline, not a dialog overlay)
// =============================================================================

@Composable
private fun NamingContent(
    pageCount: Int,
    nameError: String?,
    isSaving: Boolean,
    onSave: (name: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.scan_name_title, pageCount),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.scan_name_hint_extension),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.scan_name_label)) },
            singleLine = true,
            isError = nameError != null,
            supportingText = if (nameError != null) {
                { Text(nameError) }
            } else null,
            enabled = !isSaving,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                onSave(name.trim())
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (isSaving) {
            CircularProgressIndicator()
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        keyboard?.hide()
                        onSave(name.trim())
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(R.string.action_save))
                }
            }
        }
    }
}

// =============================================================================
// Permission denied
// =============================================================================

@Composable
private fun CameraPermissionDeniedContent(
    isPermanentlyDenied: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.permission_camera_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.permission_camera_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (isPermanentlyDenied) {
            Button(onClick = onOpenSettings) {
                Text(text = stringResource(R.string.permission_camera_open_settings))
            }
        } else {
            Button(onClick = onRequestPermission) {
                Text(text = stringResource(R.string.permission_camera_button))
            }
        }
    }
}

// =============================================================================
// Error
// =============================================================================

@Composable
private fun ErrorContent(
    message: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNavigateBack) {
            Text(text = stringResource(R.string.action_back))
        }
    }
}

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.hape.pdfscan.scan.DocumentCorners
import com.hape.pdfscan.scan.DocumentGeometry
import com.hape.pdfscan.scan.FloatPoint
import com.hape.pdfscan.storage.ScanFileRepository
import com.hape.pdfscan.storage.ScanFolderSettings
import java.io.File
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

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
                    val smoothingStrength by viewModel.smoothingStrength.collectAsState()
                    val candidateOverlayEnabled by viewModel.showCandidateOverlay.collectAsState()
                    CameraPreviewContent(
                        latestDetection = state.latestDetection,
                        showCandidates = isDebuggable && candidateOverlayEnabled,
                        autoCaptureState = autoCaptureState,
                        diagnostics = if (isDebuggable) diagnostics else null,
                        smoothingStrength = smoothingStrength,
                        isCapturing = state.isCapturing,
                        onSmoothingStrengthChange = viewModel::onSmoothingStrengthChanged,
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
                        onAdjustCrop = { viewModel.onOpenCropAdjustment() },
                        onDiscard = { viewModel.onDiscardPage() },
                        onAddAnother = { viewModel.onAddAnotherPage() },
                        onFinish = { viewModel.onFinishScanning() },
                    )
                }

                is ScanUiState.AdjustCrop -> {
                    AdjustCropContent(
                        rawBitmap = state.rawBitmap,
                        initialCorners = state.initialCorners,
                        onApply = { viewModel.onApplyCrop(it) },
                        onCancel = { viewModel.onCancelCrop() },
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
    showCandidates: Boolean,
    autoCaptureState: AutoCaptureState,
    diagnostics: String?,
    smoothingStrength: Int,
    isCapturing: Boolean,
    onSmoothingStrengthChange: (Int) -> Unit,
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
                showCandidates = showCandidates,
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

        if (!isCapturing && autoCaptureState is AutoCaptureState.Stable) {
            CountdownDisplay(
                remainingSeconds = autoCaptureState.remainingSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
            )
        }

        if (isCapturing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.5.dp,
                    )
                    Text(
                        text = stringResource(R.string.scan_processing_photo),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Smoothing slider sits above the capture button (72 dp + 32 dp padding) so it never covers it
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 112.dp)
                .fillMaxWidth(0.7f)
                .background(Color(0x99000000), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.scan_smoothing_label, smoothingStrength),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = smoothingStrength.toFloat(),
                onValueChange = { onSmoothingStrengthChange(it.roundToInt()) },
                valueRange = 0f..10f,
                steps = 9,
                enabled = !isCapturing,
            )
        }

        CaptureButton(
            onClick = onCapture,
            enabled = !isCapturing,
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
    showCandidates: Boolean,
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

        if (showCandidates) {
            // Debug only: best distinct candidates (1 yellow, 2 red, 3 green), thin lines
            val colors = listOf(Color(0xFFFFEB3B), Color(0xFFF44336), Color(0xFF4CAF50))
            detection.topCandidates.forEachIndexed { i, c ->
                val pts = c.corners.toList().map { mapPoint(it) }
                val p = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
                    close()
                }
                drawPath(p, color = colors[i % colors.size], style = Stroke(width = 2f))
            }
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
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(72.dp),
    ) {
        Icon(
            painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_camera),
            contentDescription = stringResource(R.string.action_capture_photo),
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.5f),
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
    onAdjustCrop: () -> Unit,
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
        Spacer(modifier = Modifier.height(4.dp))
        if (page.rawFile != null) {
            OutlinedButton(
                onClick = onAdjustCrop,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.action_adjust_crop))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
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
// Manual Crop Adjustment
// =============================================================================

@Composable
private fun AdjustCropContent(
    rawBitmap: android.graphics.Bitmap,
    initialCorners: DocumentCorners,
    onApply: (DocumentCorners) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var corners by remember { mutableStateOf(initialCorners) }
    var activeCornerIndex by remember { mutableStateOf<Int?>(null) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val rawWidth = rawBitmap.width.toFloat()
    val rawHeight = rawBitmap.height.toFloat()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top instruction bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.crop_hint),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    corners = DocumentCorners(
                        topLeft = FloatPoint(0f, 0f),
                        topRight = FloatPoint(rawWidth, 0f),
                        bottomRight = FloatPoint(rawWidth, rawHeight),
                        bottomLeft = FloatPoint(0f, rawHeight),
                    )
                }
            ) {
                Text(text = stringResource(R.string.action_reset_crop), color = Color(0xFF38B6FF))
            }
        }

        // Image canvas with interactive drag overlay
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .onSizeChanged { containerSize = it }
                .pointerInput(containerSize, rawWidth, rawHeight) {
                    if (containerSize.width <= 0 || containerSize.height <= 0) return@pointerInput

                    val scale = min(
                        containerSize.width.toFloat() / rawWidth,
                        containerSize.height.toFloat() / rawHeight
                    )
                    val displayW = rawWidth * scale
                    val displayH = rawHeight * scale
                    val offsetX = (containerSize.width - displayW) / 2f
                    val offsetY = (containerSize.height - displayH) / 2f

                    fun toScreen(p: FloatPoint): Offset =
                        Offset(offsetX + p.x * scale, offsetY + p.y * scale)

                    val touchRadiusPx = 48.dp.toPx()

                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val screenPts = listOf(
                                toScreen(corners.topLeft),
                                toScreen(corners.topRight),
                                toScreen(corners.bottomRight),
                                toScreen(corners.bottomLeft),
                            )
                            val distances = screenPts.map { hypot(startOffset.x - it.x, startOffset.y - it.y) }
                            val minIndex = distances.indices.minByOrNull { distances[it] }
                            if (minIndex != null && distances[minIndex] <= touchRadiusPx) {
                                activeCornerIndex = minIndex
                            } else {
                                activeCornerIndex = null
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val idx = activeCornerIndex ?: return@detectDragGestures
                            val dxImg = dragAmount.x / scale
                            val dyImg = dragAmount.y / scale
                            corners = when (idx) {
                                0 -> corners.copy(topLeft = FloatPoint((corners.topLeft.x + dxImg).coerceIn(0f, rawWidth), (corners.topLeft.y + dyImg).coerceIn(0f, rawHeight)))
                                1 -> corners.copy(topRight = FloatPoint((corners.topRight.x + dxImg).coerceIn(0f, rawWidth), (corners.topRight.y + dyImg).coerceIn(0f, rawHeight)))
                                2 -> corners.copy(bottomRight = FloatPoint((corners.bottomRight.x + dxImg).coerceIn(0f, rawWidth), (corners.bottomRight.y + dyImg).coerceIn(0f, rawHeight)))
                                3 -> corners.copy(bottomLeft = FloatPoint((corners.bottomLeft.x + dxImg).coerceIn(0f, rawWidth), (corners.bottomLeft.y + dyImg).coerceIn(0f, rawHeight)))
                                else -> corners
                            }
                        },
                        onDragEnd = {
                            activeCornerIndex = null
                        },
                        onDragCancel = {
                            activeCornerIndex = null
                        }
                    )
                }
        ) {
            Image(
                bitmap = rawBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                if (size.width <= 0 || size.height <= 0) return@Canvas
                val scale = min(
                    size.width / rawWidth,
                    size.height / rawHeight
                )
                val displayW = rawWidth * scale
                val displayH = rawHeight * scale
                val offsetX = (size.width - displayW) / 2f
                val offsetY = (size.height - displayH) / 2f

                fun map(p: FloatPoint): Offset =
                    Offset(offsetX + p.x * scale, offsetY + p.y * scale)

                val tl = map(corners.topLeft)
                val tr = map(corners.topRight)
                val br = map(corners.bottomRight)
                val bl = map(corners.bottomLeft)

                // Fill polygon
                val path = Path().apply {
                    moveTo(tl.x, tl.y)
                    lineTo(tr.x, tr.y)
                    lineTo(br.x, br.y)
                    lineTo(bl.x, bl.y)
                    close()
                }
                drawPath(path, color = Color(0x3300AAFF))
                drawPath(path, color = Color(0xFF00AAFF), style = Stroke(width = 3.dp.toPx()))

                // Draw handles
                val pts = listOf(tl, tr, br, bl)
                for (i in pts.indices) {
                    val pt = pts[i]
                    val isActive = i == activeCornerIndex
                    val outerRadius = if (isActive) 18.dp.toPx() else 14.dp.toPx()
                    val innerRadius = if (isActive) 10.dp.toPx() else 7.dp.toPx()

                    // Semi-transparent halo
                    drawCircle(
                        color = Color(0x6600AAFF),
                        radius = outerRadius + 6.dp.toPx(),
                        center = pt
                    )
                    // White outer ring
                    drawCircle(
                        color = Color.White,
                        radius = outerRadius,
                        center = pt
                    )
                    // Cyan filled center
                    drawCircle(
                        color = Color(0xFF0088EE),
                        radius = innerRadius,
                        center = pt
                    )
                }
            }
        }

        // Bottom action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_cancel))
            }
            Button(
                onClick = { onApply(corners) },
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.action_apply_crop))
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

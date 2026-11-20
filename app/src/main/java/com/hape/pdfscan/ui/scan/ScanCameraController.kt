package com.hape.pdfscan.ui.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.hape.pdfscan.BuildConfig
import com.hape.pdfscan.R
import com.hape.pdfscan.scan.DetectionStats
import com.hape.pdfscan.scan.DocumentDetector
import com.hape.pdfscan.scan.initOpenCv
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScanCameraController"
private const val STATS_TAG = "EdgeStats"
private const val CAND_TAG = "EdgeCand"

/**
 * One line per frame with the top candidates: rank, mask, score, area fraction, edge support
 * and the four corners as fractions (0..1) of the image size. Numbers only.
 */
private fun candidateLog(result: com.hape.pdfscan.scan.DetectionResult): String {
    val w = result.imageWidth.toFloat()
    val h = result.imageHeight.toFloat()
    return result.topCandidates.mapIndexed { i, c ->
        val pts = c.corners.toList().joinToString(",") { "%.2f/%.2f".format(it.x / w, it.y / h) }
        "c${i + 1}[m=${c.maskIndex} s=${"%.2f".format(c.score)} a=${"%.2f".format(c.areaFraction)} " +
            "e=${"%.2f".format(c.edgeSupport)} $pts]"
    }.joinToString(" ")
}

/**
 * Manages the CameraX lifecycle for the Scan screen.
 *
 * - [bindCamera] binds [Preview], [ImageAnalysis] (live detection) and [ImageCapture].
 * - Live results go to [ScanViewModel.onDetectionResult].
 * - [capture] takes a photo in memory, re-runs detection on the full-resolution image,
 *   applies the perspective crop and posts the result to [ScanViewModel.onCaptureDone].
 *
 * Call [unbind] when the camera preview leaves the screen and [release] when the screen
 * is disposed.
 */
class ScanCameraController(
    private val context: Context,
    private val viewModel: ScanViewModel,
) {
    private var imageCapture: ImageCapture? = null
    private var analysisExecutor: ExecutorService? = null
    private val processingExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val capturing = AtomicBoolean(false)

    private val openCvReady: Boolean by lazy {
        initOpenCv().also { viewModel.onDiagnostics("OpenCV ready=$it") }
    }
    private var frameCount = 0
    private val detectionStats = DetectionStats()

    fun bindCamera(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        analysisExecutor?.shutdown()
        detectionStats.reset()
        val executor = Executors.newSingleThreadExecutor().also { analysisExecutor = it }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
                .also { imageCapture = it }

            // Moderate analysis resolution keeps CPU usage low
            val analysisSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { imageProxy -> analyzeFrame(imageProxy) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis, capture,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed: ${e.javaClass.simpleName}")
                viewModel.onCameraError(R.string.error_camera_bind)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Unbinds the camera and stops live analysis (e.g. while a captured image is shown). */
    fun unbind() {
        cameraProvider?.unbindAll()
        imageCapture = null
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }

    /** Releases everything, including the post-processing thread. */
    fun release() {
        unbind()
        processingExecutor.shutdown()
    }

    fun capture(cacheDir: File) {
        val captureUseCase = imageCapture ?: run {
            viewModel.onDiagnostics("capture: kein ImageCapture gebunden")
            return
        }
        if (!capturing.compareAndSet(false, true)) return
        viewModel.onCaptureStarted()
        viewModel.onDiagnostics("capture: gestartet")

        captureUseCase.takePicture(
            processingExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        viewModel.onDiagnostics("capture: Foto ${image.width}x${image.height}")
                        processCapture(image, cacheDir)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Capture processing crashed: ${t.javaClass.simpleName}: ${t.message}")
                        viewModel.onCameraError(R.string.error_camera_capture)
                    } finally {
                        image.close()
                        capturing.set(false)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture error: ${exception.javaClass.simpleName}")
                    capturing.set(false)
                    viewModel.onCameraError(R.string.error_camera_capture)
                }
            },
        )
    }

    // -------------------------------------------------------------------------

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!openCvReady) return
            val bitmap = imageProxy.toUprightBitmap() ?: return
            try {
                val result = DocumentDetector.detect(bitmap)
                viewModel.onDetectionResult(result)
                val stats = detectionStats.add(result)
                if (BuildConfig.DEBUG) {
                    // Numbers only, no paths or file names
                    Log.d(
                        STATS_TAG,
                        "mask=${result.winnerMask} score=${"%.3f".format(result.winnerScore)} " +
                            "cand=${result.candidateCount} fb=${result.isFallback} " +
                            "jumps=${stats.jumps} drop=${stats.dropouts} " +
                            "meanDrift=${"%.1f".format(stats.meanDrift)} " +
                            "maxDrift=${"%.1f".format(stats.maxDrift)} " +
                            "n=${stats.frames} ${result.durationMs}ms"
                    )
                    Log.d(CAND_TAG, candidateLog(result))
                }
                if (++frameCount % 5 == 1) {
                    viewModel.onDiagnostics(
                        "Frame ${bitmap.width}x${bitmap.height} Stufe=${result.stage} " +
                            "fallback=${result.isFallback} ${result.durationMs}ms " +
                            "mask=${result.winnerMask} cand=${result.candidateCount} " +
                            "Spruenge=${stats.jumps}/${stats.frames} Aussetzer=${stats.dropouts}/${stats.frames} " +
                            "Drift=${"%.1f".format(stats.meanDrift)}/${"%.1f".format(stats.maxDrift)}px"
                    )
                }
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame analysis failed: ${t.javaClass.simpleName}: ${t.message}")
            viewModel.onDiagnostics("Analyse-Fehler: ${t.javaClass.simpleName}")
        } finally {
            imageProxy.close()
        }
    }

    /** Runs on [processingExecutor]. */
    private fun processCapture(image: ImageProxy, cacheDir: File) {
        if (!openCvReady) {
            viewModel.onCameraError(R.string.error_opencv_init)
            return
        }
        try {
            val photo = image.toUprightBitmap() ?: run {
                viewModel.onCameraError(R.string.error_camera_capture)
                return
            }
            val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.jpg")
            FileOutputStream(rawFile).use { out ->
                photo.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            // Re-detect on the full-resolution photo for a sharp crop
            val (cropped, detectedCorners) = try {
                val detection = DocumentDetector.detect(photo)
                val c = DocumentDetector.crop(photo, detection.corners, viewModel.maxDimension)
                Pair(c, detection.corners)
            } finally {
                photo.recycle()
            }
            if (cropped == null) {
                viewModel.onCameraError(R.string.error_camera_capture)
                return
            }
            viewModel.onDiagnostics(
                "capture: Crop ${cropped.width}x${cropped.height}"
            )
            val tempFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, viewModel.jpegQuality, out)
            }
            viewModel.onCaptureDone(
                croppedBitmap = cropped,
                tempFile = tempFile,
                rawFile = rawFile,
                detectedCorners = detectedCorners,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Post-capture processing failed: ${t.javaClass.simpleName}: ${t.message}")
            viewModel.onDiagnostics("Capture-Fehler: ${t.javaClass.simpleName}")
            viewModel.onCameraError(R.string.error_camera_capture)
        }
    }
}

/** Converts an [ImageProxy] to a bitmap rotated upright according to its rotation info. */
private fun ImageProxy.toUprightBitmap(): Bitmap? {
    val raw = try {
        toBitmap()
    } catch (e: Exception) {
        Log.e(TAG, "ImageProxy to Bitmap failed: ${e.javaClass.simpleName}")
        return null
    }
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return raw
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
}

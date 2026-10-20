package com.hape.pdfscan.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hape.pdfscan.R
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfFile: File,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: PdfViewerViewModel = viewModel(factory = PdfViewerViewModel.Factory(pdfFile))
    val state by vm.state.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    val title = if (state is PdfViewerState.Ready) {
                        (state as PdfViewerState.Ready).fileName
                    } else {
                        stringResource(R.string.title_pdf_viewer)
                    }
                    Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
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
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (val s = state) {
                is PdfViewerState.Loading -> CircularProgressIndicator(color = Color.White)

                is PdfViewerState.Error -> Text(
                    text = stringResource(s.messageRes),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )

                is PdfViewerState.Ready -> {
                    val pages = s.pages
                    if (pages.size == 1) {
                        // Single page – show directly with zoom
                        ZoomablePage(
                            bitmap = pages[0],
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        // Multiple pages – HorizontalPager, zoom resets on page change
                        val pagerState = rememberPagerState { pages.size }
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { pageIndex ->
                            ZoomablePage(
                                bitmap = pages[pageIndex],
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Zoomable single page
// ---------------------------------------------------------------------------

@Composable
private fun ZoomablePage(
    bitmap: android.graphics.Bitmap,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .pointerInput(bitmap) {
                awaitEachGesture {
                    // Wait for first finger down
                    awaitFirstDown(requireUnconsumed = false)

                    // Watch events; only consume when multi-touch or already zoomed in
                    do {
                        val event = awaitPointerEvent()
                        val isMultiTouch = event.changes.size > 1

                        if (isMultiTouch || scale > 1f) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val newScale = (scale * zoomChange).coerceIn(1f, 8f)
                            scale = newScale
                            if (newScale > 1f) {
                                val maxOff = (newScale - 1f) * size.width / 2f
                                offsetX = (offsetX + panChange.x).coerceIn(-maxOff, maxOff)
                                offsetY = (offsetY + panChange.y).coerceIn(-maxOff, maxOff)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            // Consume so the pager doesn't also react
                            event.changes.forEach { it.consume() }
                        }
                        // Single-finger at scale==1: don't consume → HorizontalPager swipes
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

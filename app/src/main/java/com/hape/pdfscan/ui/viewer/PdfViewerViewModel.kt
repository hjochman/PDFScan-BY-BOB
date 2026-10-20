package com.hape.pdfscan.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "PdfViewerViewModel"

/** Render resolution scale factor relative to screen density. 2 = sharp on most displays. */
private const val RENDER_SCALE = 2

sealed interface PdfViewerState {
    data object Loading : PdfViewerState
    data class Ready(val pages: List<Bitmap>, val fileName: String) : PdfViewerState
    data class Error(val messageRes: Int) : PdfViewerState
}

class PdfViewerViewModel(private val pdfFile: File) : ViewModel() {

    private val _state = MutableStateFlow<PdfViewerState>(PdfViewerState.Loading)
    val state: StateFlow<PdfViewerState> = _state.asStateFlow()

    init {
        loadPages()
    }

    private fun loadPages() {
        viewModelScope.launch {
            val bitmaps = withContext(Dispatchers.IO) { renderPages() }
            _state.value = if (bitmaps != null) {
                PdfViewerState.Ready(bitmaps, pdfFile.name)
            } else {
                PdfViewerState.Error(com.hape.pdfscan.R.string.error_pdf_open)
            }
        }
    }

    private fun renderPages(): List<Bitmap>? {
        return try {
            val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            fd.use {
                val renderer = PdfRenderer(fd)
                renderer.use {
                    (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            val w = page.width * RENDER_SCALE
                            val h = page.height * RENDER_SCALE
                            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            // Fill white background (PDF pages are transparent by default)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "PDF render failed: ${t.javaClass.simpleName}")
            null
        }
    }

    override fun onCleared() {
        val current = _state.value
        if (current is PdfViewerState.Ready) {
            current.pages.forEach { it.recycle() }
        }
    }

    class Factory(private val pdfFile: File) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PdfViewerViewModel(pdfFile) as T
    }
}

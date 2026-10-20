package com.hape.pdfscan.ui.about

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hape.pdfscan.BuildConfig
import com.hape.pdfscan.storage.appDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val KEY_LAST_SEEN_VERSION = stringPreferencesKey("about_last_seen_version")

data class AboutUiState(
    /** True while we haven't read the stored version yet. */
    val isLoading: Boolean = true,
    /** Whether the About screen was triggered automatically (first start / update). */
    val isAutoShown: Boolean = false,
)

class AboutViewModel(private val context: Context) : ViewModel() {

    private val _uiState = MutableStateFlow(AboutUiState())
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = context.appDataStore.data.first()
            val lastSeen = prefs[KEY_LAST_SEEN_VERSION] ?: ""
            val current = BuildConfig.VERSION_NAME
            val isAuto = lastSeen != current
            _uiState.value = AboutUiState(isLoading = false, isAutoShown = isAuto)
        }
    }

    /** Call when the user closes the About screen. */
    fun onClose() {
        viewModelScope.launch {
            context.appDataStore.edit { prefs ->
                prefs[KEY_LAST_SEEN_VERSION] = BuildConfig.VERSION_NAME
            }
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AboutViewModel(context.applicationContext) as T
    }
}

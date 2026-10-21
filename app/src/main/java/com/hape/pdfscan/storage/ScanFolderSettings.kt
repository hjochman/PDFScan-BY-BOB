package com.hape.pdfscan.storage

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/** Single app-wide Preferences DataStore ("settings"). Later sub-tasks add their keys to the same store. */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Persists the scan folder path. Without a stored value the default `/Scans` in the root of the
 * internal storage is used.
 *
 * The path is stored as given; checking that a user-chosen folder is valid (exists, writable,
 * on internal storage) is the job of the settings screen (sub-task 7).
 */
class ScanFolderSettings(
    private val dataStore: DataStore<Preferences>,
    private val defaultFolder: () -> File = ::defaultScanFolder,
) {

    constructor(context: Context) : this(context.applicationContext.appDataStore)

    /** The configured scan folder (or the default). Emits again whenever it changes. */
    val scanFolder: Flow<File> = dataStore.data
        .map { prefs -> prefs[KEY_SCAN_FOLDER]?.takeIf { it.isNotBlank() }?.let(::File) ?: defaultFolder() }
        .distinctUntilChanged()

    suspend fun currentScanFolder(): File = scanFolder.first()

    /** Stores [folder] (absolute path) as the new scan folder. */
    suspend fun setScanFolder(folder: File) {
        dataStore.edit { it[KEY_SCAN_FOLDER] = folder.absolutePath }
    }

    /** Removes the custom path, so the default `/Scans` applies again. */
    suspend fun resetScanFolder() {
        dataStore.edit { it.remove(KEY_SCAN_FOLDER) }
    }

    // ------------------------------------------------------------------
    // Auto-capture countdown seconds (0 = disabled / manual only)
    // ------------------------------------------------------------------

    /** Emits the configured countdown duration in seconds. Default is 3; 0 means disabled. */
    val countdownSeconds: Flow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_COUNTDOWN_SECONDS] ?: DEFAULT_COUNTDOWN_SECONDS }
        .distinctUntilChanged()

    suspend fun currentCountdownSeconds(): Int = countdownSeconds.first()

    suspend fun setCountdownSeconds(seconds: Int) {
        dataStore.edit { it[KEY_COUNTDOWN_SECONDS] = seconds.coerceIn(0, 10) }
    }

    suspend fun resetCountdownSeconds() {
        dataStore.edit { it.remove(KEY_COUNTDOWN_SECONDS) }
    }

    // ------------------------------------------------------------------
    // Image resolution (longest side in px) and JPEG quality for new scans
    // ------------------------------------------------------------------

    val maxDimension: Flow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_MAX_DIMENSION] ?: DEFAULT_MAX_DIMENSION }
        .distinctUntilChanged()

    suspend fun setMaxDimension(pixels: Int) {
        dataStore.edit { it[KEY_MAX_DIMENSION] = pixels.coerceIn(MIN_MAX_DIMENSION, MAX_MAX_DIMENSION) }
    }

    val jpegQuality: Flow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_JPEG_QUALITY] ?: DEFAULT_JPEG_QUALITY }
        .distinctUntilChanged()

    suspend fun setJpegQuality(percent: Int) {
        dataStore.edit { it[KEY_JPEG_QUALITY] = percent.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY) }
    }

    // ------------------------------------------------------------------
    // Edge smoothing strength of the live overlay (0 = off .. 10 = strong)
    // ------------------------------------------------------------------

    val smoothingStrength: Flow<Int> = dataStore.data
        .map { prefs -> prefs[KEY_SMOOTHING_STRENGTH] ?: DEFAULT_SMOOTHING_STRENGTH }
        .distinctUntilChanged()

    suspend fun setSmoothingStrength(strength: Int) {
        dataStore.edit {
            it[KEY_SMOOTHING_STRENGTH] = strength.coerceIn(MIN_SMOOTHING_STRENGTH, MAX_SMOOTHING_STRENGTH)
        }
    }

    companion object {
        const val DEFAULT_FOLDER_NAME = "Scans"
        const val DEFAULT_SMOOTHING_STRENGTH = 5
        const val MIN_SMOOTHING_STRENGTH = 0
        const val MAX_SMOOTHING_STRENGTH = 10
        const val DEFAULT_COUNTDOWN_SECONDS = 3
        const val MAX_COUNTDOWN_SECONDS = 10
        const val DEFAULT_MAX_DIMENSION = 2000
        const val MIN_MAX_DIMENSION = 1200
        const val MAX_MAX_DIMENSION = 3000
        const val MAX_DIMENSION_STEP = 100
        const val DEFAULT_JPEG_QUALITY = 80
        const val MIN_JPEG_QUALITY = 50
        const val MAX_JPEG_QUALITY = 95
        const val JPEG_QUALITY_STEP = 5
        private val KEY_SCAN_FOLDER = stringPreferencesKey("scan_folder_path")
        val KEY_COUNTDOWN_SECONDS = intPreferencesKey("auto_capture_countdown_seconds")
        private val KEY_MAX_DIMENSION = intPreferencesKey("scan_max_dimension_px")
        private val KEY_JPEG_QUALITY = intPreferencesKey("scan_jpeg_quality_percent")
        private val KEY_SMOOTHING_STRENGTH = intPreferencesKey("scan_smoothing_strength")
    }
}

/** `/Scans` in the root of the internal storage (`/storage/emulated/0/Scans`). */
fun defaultScanFolder(): File =
    File(Environment.getExternalStorageDirectory(), ScanFolderSettings.DEFAULT_FOLDER_NAME)

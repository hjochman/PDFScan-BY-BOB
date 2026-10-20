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

    companion object {
        const val DEFAULT_FOLDER_NAME = "Scans"
        const val DEFAULT_COUNTDOWN_SECONDS = 3
        const val MAX_COUNTDOWN_SECONDS = 10
        private val KEY_SCAN_FOLDER = stringPreferencesKey("scan_folder_path")
        val KEY_COUNTDOWN_SECONDS = intPreferencesKey("auto_capture_countdown_seconds")
    }
}

/** `/Scans` in the root of the internal storage (`/storage/emulated/0/Scans`). */
fun defaultScanFolder(): File =
    File(Environment.getExternalStorageDirectory(), ScanFolderSettings.DEFAULT_FOLDER_NAME)

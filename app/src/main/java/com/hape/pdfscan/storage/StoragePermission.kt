package com.hape.pdfscan.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * What the UI has to do to obtain file access.
 */
sealed interface PermissionRequest {
    /** Access is already granted, nothing to do. */
    data object NotNeeded : PermissionRequest

    /** API 29: request these permissions with `ActivityResultContracts.RequestMultiplePermissions`. */
    class RuntimePermissions(val permissions: Array<String>) : PermissionRequest

    /**
     * API 30+: start [intent] (e.g. with `startActivity`). If it throws `ActivityNotFoundException`,
     * start [fallbackIntent] instead. Re-check [StoragePermission.isGranted] when the app resumes.
     */
    class AllFilesAccessSettings(val intent: Intent, val fallbackIntent: Intent) : PermissionRequest
}

/**
 * Checks and requests the storage permission needed to access the scan folder via `java.io.File`.
 * - API 30+: "All files access" (`MANAGE_EXTERNAL_STORAGE`), granted on a system settings page.
 * - API 29: `READ`/`WRITE_EXTERNAL_STORAGE` runtime permissions (plus `requestLegacyExternalStorage`).
 */
object StoragePermission {

    private val LEGACY_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun isGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            LEGACY_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** Describes the action required to get access; [PermissionRequest.NotNeeded] if already granted. */
    fun requestFor(context: Context): PermissionRequest = when {
        isGranted(context) -> PermissionRequest.NotNeeded
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> PermissionRequest.AllFilesAccessSettings(
            intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            ),
            fallbackIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
        )
        else -> PermissionRequest.RuntimePermissions(LEGACY_PERMISSIONS.copyOf())
    }
}

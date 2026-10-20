package com.hape.pdfscan.storage

import java.io.File
import java.io.IOException

/**
 * Keeps every file operation inside the scan folder [root].
 *
 * Paths are resolved with `canonicalFile` (so `..` and symbolic links are resolved) before the
 * containment check; a path that leads out of [root] through a symlink is therefore rejected.
 * Pure `java.io`, no Android dependency.
 */
class PathGuard(private val root: File) {

    /** The canonical scan folder, or `null` if it cannot be resolved. */
    fun canonicalRoot(): File? = canonical(root)

    /**
     * Returns the canonical form of [file] if it is [root] or lies inside it, otherwise `null`.
     * Relative paths are rejected.
     */
    fun resolve(file: File): File? {
        if (!file.isAbsolute) return null
        val rootCanonical = canonicalRoot() ?: return null
        val candidate = canonical(file) ?: return null
        return candidate.takeIf { isSameOrDescendant(it, rootCanonical) }
    }

    companion object {
        private fun canonical(file: File): File? = try {
            file.canonicalFile
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

        /** `true` if [candidate] equals [ancestor] or lies below it. Both must be canonical. */
        fun isSameOrDescendant(candidate: File, ancestor: File): Boolean {
            val ancestorPath = ancestor.path
            if (candidate.path == ancestorPath) return true
            val prefix = if (ancestorPath.endsWith(File.separator)) ancestorPath else ancestorPath + File.separator
            return candidate.path.startsWith(prefix)
        }
    }
}

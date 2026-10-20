package com.hape.pdfscan.storage

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * File and folder operations inside the scan folder [root] (`java.io.File`, run on [ioDispatcher]).
 * Create a new instance when the configured scan folder changes.
 *
 * Rules that apply to all operations:
 * - Every path is resolved with `canonicalFile` and must lie inside [root] (see [PathGuard]); otherwise
 *   [StorageError.OutsideRoot]. Files returned by this class are canonical paths.
 * - New names (create, rename) are checked with [NameValidator].
 * - Nothing is overwritten. If the target exists, copy/move either fail with [StorageError.AlreadyExists]
 *   or pick a free name ([ConflictStrategy.KEEP_BOTH], e.g. `Name (2).pdf`); create/rename always fail.
 * - Folders are handled recursively. A folder can not be copied or moved into itself or a subfolder
 *   ([StorageError.InvalidTarget]). Symbolic links are never followed or copied
 *   ([StorageError.SymlinkNotSupported]) and are not listed. Deleting a link removes only the link.
 * - Errors are returned as [StorageResult.Failure]; details go only to [logger] (operation and exception
 *   class, no paths, file names or messages).
 */
class ScanFileRepository(
    private val root: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val guard = PathGuard(root)

    /** Creates the scan folder if it is missing. Returns its canonical path. */
    suspend fun ensureRoot(): StorageResult<File> = io("ensureRoot") {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw StorageException(StorageError.IoError)
        guard.canonicalRoot() ?: throw StorageException(StorageError.IoError)
    }

    /**
     * Lists [directory] (default: the scan folder): folders first, then PDFs, each sorted by name
     * (case-insensitive). Hidden entries, symbolic links and other file types are left out.
     */
    suspend fun list(directory: File = root): StorageResult<List<ScanEntry>> = io("list") {
        val dir = directoryOf(directory)
        val children = dir.listFiles() ?: throw StorageException(StorageError.IoError)
        children
            .mapNotNull { child ->
                when {
                    child.name.startsWith(".") || isSymlink(child) -> null
                    child.isDirectory -> ScanEntry(child, true, child.lastModified(), 0L)
                    child.isFile && child.name.endsWith(".pdf", ignoreCase = true) ->
                        ScanEntry(child, false, child.lastModified(), child.length())
                    else -> null
                }
            }
            .sortedWith(compareBy<ScanEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Creates the folder [name] in [parent]. Fails with [StorageError.AlreadyExists] if the name is taken. */
    suspend fun createFolder(parent: File, name: String): StorageResult<File> = io("createFolder") {
        NameValidator.validate(name)?.let { throw StorageException(StorageError.NameInvalid(it)) }
        val target = File(directoryOf(parent), name)
        if (exists(target)) throw StorageException(StorageError.AlreadyExists)
        if (!target.mkdir()) {
            throw StorageException(if (exists(target)) StorageError.AlreadyExists else StorageError.IoError)
        }
        target
    }

    /**
     * Renames [file] (file or folder) to [newName] within its folder. [newName] is the complete new
     * name including a file extension. Fails with [StorageError.AlreadyExists] if the name is taken;
     * a change of letter case only is allowed.
     */
    suspend fun rename(file: File, newName: String): StorageResult<File> = io("rename") {
        NameValidator.validate(newName)?.let { throw StorageException(StorageError.NameInvalid(it)) }
        val source = entryOf(file)
        val target = File(source.parentFile, newName)
        if (target != source) {
            val caseOnlyChange = source.name.equals(newName, ignoreCase = true) && isSameFile(source, target)
            if (exists(target) && !caseOnlyChange) throw StorageException(StorageError.AlreadyExists)
            if (!source.renameTo(target)) throw StorageException(StorageError.IoError)
        }
        target
    }

    /**
     * Deletes [file], or a folder with all its content. The scan folder itself can not be deleted
     * ([StorageError.InvalidTarget]). If a folder could only be deleted in part, the result is
     * [StorageError.PartiallyDone] (nothing deleted: [StorageError.IoError]).
     */
    suspend fun delete(file: File): StorageResult<Unit> = io("delete") {
        val state = DeleteState()
        deleteTree(entryOf(file), state)
        if (state.failed) {
            throw StorageException(if (state.deleted > 0) StorageError.PartiallyDone else StorageError.IoError)
        }
    }

    /**
     * Copies [source] (file or folder with content) into the folder [targetDirectory]. Copying into the
     * same folder is allowed and creates a second entry (name per [conflict]). If the copy fails midway,
     * the incomplete copy is removed again.
     */
    suspend fun copy(
        source: File,
        targetDirectory: File,
        conflict: ConflictStrategy = ConflictStrategy.FAIL,
    ): StorageResult<File> = io("copy") {
        val src = entryOf(source)
        val targetDir = directoryOf(targetDirectory)
        checkTransfer(src, targetDir)
        val target = targetFor(src, targetDir, conflict)
        copyEntry(src, target)
        target
    }

    /**
     * Moves [source] (file or folder with content) into the folder [targetDirectory]. First tries a
     * rename; if that is not possible (e.g. another file system), the entry is copied and then deleted.
     * Moving into the folder it is already in does nothing and returns [source] unchanged.
     *
     * If the source can not be removed after copying: nothing removed yet - the copy is undone
     * ([StorageError.IoError]); partly removed - the complete copy stays at the target and the result is
     * [StorageError.PartiallyDone].
     */
    suspend fun move(
        source: File,
        targetDirectory: File,
        conflict: ConflictStrategy = ConflictStrategy.FAIL,
    ): StorageResult<File> = io("move") {
        val src = entryOf(source)
        val targetDir = directoryOf(targetDirectory)
        checkTransfer(src, targetDir)
        if (src.parentFile == targetDir) return@io src
        val target = targetFor(src, targetDir, conflict)
        if (src.renameTo(target)) return@io target

        copyEntry(src, target)
        val state = DeleteState()
        deleteTree(src, state)
        if (state.failed) {
            if (state.deleted > 0) throw StorageException(StorageError.PartiallyDone)
            // Source untouched: undo the copy so that no duplicate stays behind.
            val undo = DeleteState().also { deleteTree(target, it) }
            throw StorageException(if (undo.failed) StorageError.PartiallyDone else StorageError.IoError)
        }
        target
    }

    /**
     * Returns [name] if it is free in [directory], otherwise the next free `Base (n).ext` (n from 2;
     * an existing ` (n)` suffix is replaced). For folders ([isDirectory]) the whole name is the base.
     */
    suspend fun suggestUniqueName(directory: File, name: String, isDirectory: Boolean): String =
        withContext(ioDispatcher) { uniqueName(directory, name, isDirectory) }

    // ---- path checks -------------------------------------------------------------------------

    /** Internal control flow: carries the [error] out of a failed operation. */
    private class StorageException(val error: StorageError) : Exception(null, null, false, false)

    /** Canonical [dir] if it is an existing folder inside the scan folder. */
    private fun directoryOf(dir: File): File {
        val canonical = guard.resolve(dir) ?: throw StorageException(StorageError.OutsideRoot)
        if (!canonical.isDirectory) throw StorageException(StorageError.NotFound)
        return canonical
    }

    /**
     * The entry [file] itself (a symbolic link is not resolved), addressed through its canonical parent.
     * Fails if its parent is outside the scan folder, it is the scan folder, or it does not exist.
     */
    private fun entryOf(file: File): File {
        val name = file.name
        if (name.isEmpty() || name == "." || name == "..") throw StorageException(StorageError.OutsideRoot)
        val self = guard.resolve(file)
        if (self != null && self == guard.canonicalRoot()) throw StorageException(StorageError.InvalidTarget)
        val parent = file.parentFile?.let(guard::resolve) ?: throw StorageException(StorageError.OutsideRoot)
        val entry = File(parent, name)
        if (!exists(entry)) throw StorageException(StorageError.NotFound)
        // A link is addressed as a link (not its target), so it may point anywhere; copy/move reject links.
        if (self == null && !isSymlink(entry)) throw StorageException(StorageError.OutsideRoot)
        return entry
    }

    /** Common checks for copy and move. */
    private fun checkTransfer(src: File, targetDir: File) {
        val error = when {
            isSymlink(src) -> StorageError.SymlinkNotSupported
            src.isDirectory && PathGuard.isSameOrDescendant(targetDir, src) -> StorageError.InvalidTarget
            src.isDirectory && containsSymlink(src) -> StorageError.SymlinkNotSupported
            else -> null
        }
        if (error != null) throw StorageException(error)
    }

    /** Target path for [src] in [targetDir] per [conflict]. */
    private fun targetFor(src: File, targetDir: File, conflict: ConflictStrategy): File {
        val plain = File(targetDir, src.name)
        if (!exists(plain)) return plain
        return when (conflict) {
            ConflictStrategy.FAIL -> throw StorageException(StorageError.AlreadyExists)
            ConflictStrategy.KEEP_BOTH -> File(targetDir, uniqueName(targetDir, src.name, src.isDirectory))
        }
    }

    // ---- file system work --------------------------------------------------------------------

    private class DeleteState {
        var deleted = 0
        var failed = false
    }

    /** Deletes [file] depth-first; symbolic links are removed as links. Stops at the first failure. */
    private fun deleteTree(file: File, state: DeleteState) {
        if (!isSymlink(file) && file.isDirectory) {
            val children = file.listFiles()
            if (children == null) {
                state.failed = true
                return
            }
            for (child in children) {
                deleteTree(child, state)
                if (state.failed) return
            }
        }
        if (file.delete()) state.deleted++ else state.failed = true
    }

    /**
     * Copies [src] to the not yet existing [dst]. On failure an incomplete copy is removed and a
     * [StorageException] is thrown ([StorageError.PartiallyDone] if the cleanup fails too).
     */
    private fun copyEntry(src: File, dst: File) {
        try {
            if (src.isDirectory) {
                if (!dst.mkdir()) {
                    throw StorageException(if (exists(dst)) StorageError.AlreadyExists else StorageError.IoError)
                }
                copyChildren(src, dst)
            } else {
                Files.copy(src.toPath(), dst.toPath())
            }
        } catch (e: FileAlreadyExistsException) {
            // Someone created the target in the meantime; it is not ours, so it is not removed.
            log("copy", e)
            throw StorageException(StorageError.AlreadyExists)
        } catch (e: IOException) {
            log("copy", e)
            throw StorageException(rollback(dst))
        } catch (e: SecurityException) {
            log("copy", e)
            throw StorageException(rollback(dst))
        }
    }

    private fun copyChildren(srcDir: File, dstDir: File) {
        val children = srcDir.listFiles() ?: throw IOException("Cannot list folder")
        for (child in children) {
            val target = File(dstDir, child.name)
            if (child.isDirectory) {
                if (!target.mkdir()) throw IOException("Cannot create folder")
                copyChildren(child, target)
            } else {
                Files.copy(child.toPath(), target.toPath())
            }
        }
    }

    private fun rollback(dst: File): StorageError {
        val state = DeleteState()
        if (exists(dst)) deleteTree(dst, state)
        return if (state.failed) StorageError.PartiallyDone else StorageError.IoError
    }

    private fun containsSymlink(dir: File): Boolean {
        val children = dir.listFiles() ?: throw IOException("Cannot list folder")
        return children.any { isSymlink(it) || (it.isDirectory && containsSymlink(it)) }
    }

    private fun uniqueName(directory: File, name: String, isDirectory: Boolean): String {
        if (!exists(File(directory, name))) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = !isDirectory && dot > 0
        val stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        val base = SUFFIX.matchEntire(stem)?.groupValues?.get(1) ?: stem
        var n = 2
        while (true) {
            val candidate = "$base ($n)$extension"
            if (!exists(File(directory, candidate))) return candidate
            n++
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private suspend fun <T> io(operation: String, block: () -> T): StorageResult<T> =
        withContext(ioDispatcher) {
            try {
                StorageResult.Success(block())
            } catch (e: StorageException) {
                StorageResult.Failure(e.error)
            } catch (e: IOException) {
                log(operation, e)
                StorageResult.Failure(StorageError.IoError)
            } catch (e: SecurityException) {
                log(operation, e)
                StorageResult.Failure(StorageError.IoError)
            }
        }

    /** Logs the operation and exception class only - no paths, names or messages. */
    private fun log(operation: String, e: Exception) {
        logger("Storage operation '$operation' failed: ${e.javaClass.simpleName}")
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun isSymlink(file: File) = Files.isSymbolicLink(file.toPath())

    private fun isSameFile(a: File, b: File) = try {
        Files.isSameFile(a.toPath(), b.toPath())
    } catch (_: IOException) {
        false
    }

    private companion object {
        const val TAG = "ScanFileRepository"
        val SUFFIX = Regex("""(.*) \((\d+)\)""")
    }
}

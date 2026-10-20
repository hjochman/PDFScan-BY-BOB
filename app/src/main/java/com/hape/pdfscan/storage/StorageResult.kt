package com.hape.pdfscan.storage

import java.io.File

/** Outcome of a storage operation. The UI maps [StorageError] to its own (localised) messages. */
sealed interface StorageResult<out T> {
    data class Success<T>(val value: T) : StorageResult<T>
    data class Failure(val error: StorageError) : StorageResult<Nothing>

    fun getOrNull(): T? = (this as? Success<T>)?.value
}

/** Why a storage operation failed. Never carries raw exception messages or paths. */
sealed interface StorageError {
    /** The given file or folder name is not allowed, see [NameProblem]. */
    data class NameInvalid(val problem: NameProblem) : StorageError

    /** The target already exists (and [ConflictStrategy.FAIL] applies, or the operation never renames). */
    data object AlreadyExists : StorageError

    /** A path resolves (after `canonicalPath`, so also via symlinks) to a place outside the scan folder. */
    data object OutsideRoot : StorageError

    /** Source or target folder does not exist (or is not a folder where one is required). */
    data object NotFound : StorageError

    /** Not allowed: operating on the scan folder itself, or moving/copying a folder into itself or a subfolder. */
    data object InvalidTarget : StorageError

    /** A symbolic link was found inside a folder tree that should be copied. Links are not followed. */
    data object SymlinkNotSupported : StorageError

    /** The operation failed; nothing was changed (partial results were rolled back). */
    data object IoError : StorageError

    /**
     * The operation failed after it had already changed something that could not be undone:
     * - delete: part of the content has been removed,
     * - move across file systems: the copy at the target is complete, but the source could not be removed fully.
     */
    data object PartiallyDone : StorageError
}

/** Reason for [StorageError.NameInvalid]. */
enum class NameProblem {
    EMPTY,
    TOO_LONG,
    CONTROL_CHARACTER,

    /** Contains a character outside the allowlist, e.g. `/ \ : * ? " < > |`. */
    FORBIDDEN_CHARACTER,

    /** Contains `..`, or starts/ends with a dot. */
    DOT_SEQUENCE,
    LEADING_OR_TRAILING_SPACE,
}

/** What to do if the target name of a copy/move is taken. Nothing is ever overwritten. */
enum class ConflictStrategy {
    /** Report [StorageError.AlreadyExists]. */
    FAIL,

    /** Use a free name such as `Name (2).pdf` (see [ScanFileRepository.suggestUniqueName]). */
    KEEP_BOTH,
}

/** One entry of a folder listing. */
data class ScanEntry(
    val file: File,
    val isDirectory: Boolean,
    val lastModified: Long,
    /** Size in bytes; 0 for folders. */
    val size: Long,
) {
    val name: String get() = file.name
}

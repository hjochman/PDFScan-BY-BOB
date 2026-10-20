package com.hape.pdfscan.ui.main

import androidx.annotation.StringRes
import com.hape.pdfscan.R
import com.hape.pdfscan.storage.NameProblem
import com.hape.pdfscan.storage.StorageError

object StorageErrorMapper {
    @StringRes
    fun mapToStringRes(error: StorageError): Int = when (error) {
        is StorageError.NameInvalid -> mapNameProblem(error.problem)
        StorageError.AlreadyExists -> R.string.error_already_exists
        StorageError.OutsideRoot -> R.string.error_outside_root
        StorageError.NotFound -> R.string.error_not_found
        StorageError.InvalidTarget -> R.string.error_invalid_target
        StorageError.SymlinkNotSupported -> R.string.error_symlink_not_supported
        StorageError.IoError -> R.string.error_io
        StorageError.PartiallyDone -> R.string.error_partially_done
    }

    @StringRes
    fun mapNameProblem(problem: NameProblem): Int = when (problem) {
        NameProblem.EMPTY -> R.string.error_name_empty
        NameProblem.TOO_LONG -> R.string.error_name_too_long
        NameProblem.CONTROL_CHARACTER -> R.string.error_name_control_char
        NameProblem.FORBIDDEN_CHARACTER -> R.string.error_name_forbidden_char
        NameProblem.DOT_SEQUENCE -> R.string.error_name_dot_sequence
        NameProblem.LEADING_OR_TRAILING_SPACE -> R.string.error_name_leading_trailing_space
    }
}

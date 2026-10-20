package com.hape.pdfscan.ui.main

import com.hape.pdfscan.R
import com.hape.pdfscan.storage.NameProblem
import com.hape.pdfscan.storage.StorageError
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageErrorMapperTest {

    @Test
    fun mapsAllStorageErrorsToValidStringResources() {
        assertEquals(R.string.error_already_exists, StorageErrorMapper.mapToStringRes(StorageError.AlreadyExists))
        assertEquals(R.string.error_outside_root, StorageErrorMapper.mapToStringRes(StorageError.OutsideRoot))
        assertEquals(R.string.error_not_found, StorageErrorMapper.mapToStringRes(StorageError.NotFound))
        assertEquals(R.string.error_invalid_target, StorageErrorMapper.mapToStringRes(StorageError.InvalidTarget))
        assertEquals(R.string.error_symlink_not_supported, StorageErrorMapper.mapToStringRes(StorageError.SymlinkNotSupported))
        assertEquals(R.string.error_io, StorageErrorMapper.mapToStringRes(StorageError.IoError))
        assertEquals(R.string.error_partially_done, StorageErrorMapper.mapToStringRes(StorageError.PartiallyDone))
        assertEquals(R.string.error_name_empty, StorageErrorMapper.mapToStringRes(StorageError.NameInvalid(NameProblem.EMPTY)))
    }

    @Test
    fun mapsAllNameProblemsToValidStringResources() {
        assertEquals(R.string.error_name_empty, StorageErrorMapper.mapNameProblem(NameProblem.EMPTY))
        assertEquals(R.string.error_name_too_long, StorageErrorMapper.mapNameProblem(NameProblem.TOO_LONG))
        assertEquals(R.string.error_name_control_char, StorageErrorMapper.mapNameProblem(NameProblem.CONTROL_CHARACTER))
        assertEquals(R.string.error_name_forbidden_char, StorageErrorMapper.mapNameProblem(NameProblem.FORBIDDEN_CHARACTER))
        assertEquals(R.string.error_name_dot_sequence, StorageErrorMapper.mapNameProblem(NameProblem.DOT_SEQUENCE))
        assertEquals(R.string.error_name_leading_trailing_space, StorageErrorMapper.mapNameProblem(NameProblem.LEADING_OR_TRAILING_SPACE))
    }
}

package com.hape.pdfscan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameValidatorTest {

    @Test
    fun validNamesAreAccepted() {
        listOf(
            "Rechnung.pdf",
            "Scan 2024-01-05 (2).pdf",
            "Müller & Söhne",
            "日本語のファイル.pdf",
            "a",
            "Report_v1.2,final'[x]+#@!=~.pdf",
        ).forEach { assertNull("'$it' should be valid", NameValidator.validate(it)) }
    }

    @Test
    fun emptyAndBlankNamesAreRejected() {
        assertEquals(NameProblem.EMPTY, NameValidator.validate(""))
        assertEquals(NameProblem.EMPTY, NameValidator.validate("   "))
    }

    @Test
    fun reservedAndPathCharactersAreRejected() {
        listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "/etc", "..\\x").forEach {
            assertEquals("'$it'", NameProblem.FORBIDDEN_CHARACTER, NameValidator.validate(it))
        }
    }

    @Test
    fun controlCharactersAreRejected() {
        assertEquals(NameProblem.CONTROL_CHARACTER, NameValidator.validate("a\u0000b"))
        assertEquals(NameProblem.CONTROL_CHARACTER, NameValidator.validate("a\nb"))
        assertEquals(NameProblem.CONTROL_CHARACTER, NameValidator.validate("a\tb"))
    }

    @Test
    fun dotSequencesAreRejected() {
        listOf("..", ".", "a..b", ".hidden", "name.").forEach {
            assertEquals("'$it'", NameProblem.DOT_SEQUENCE, NameValidator.validate(it))
        }
    }

    @Test
    fun leadingAndTrailingSpacesAreRejected() {
        assertEquals(NameProblem.LEADING_OR_TRAILING_SPACE, NameValidator.validate(" a"))
        assertEquals(NameProblem.LEADING_OR_TRAILING_SPACE, NameValidator.validate("a "))
    }

    @Test
    fun lengthIsLimitedInCharactersAndBytes() {
        assertNull(NameValidator.validate("a".repeat(NameValidator.MAX_LENGTH)))
        assertEquals(NameProblem.TOO_LONG, NameValidator.validate("a".repeat(NameValidator.MAX_LENGTH + 1)))
        // 100 characters, but 300 UTF-8 bytes
        assertEquals(NameProblem.TOO_LONG, NameValidator.validate("日".repeat(NameValidator.MAX_LENGTH)))
    }
}

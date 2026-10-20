package com.hape.pdfscan.storage

/**
 * Allowlist validation for file and folder names (a single path component, no extension handling).
 *
 * Allowed: letters and digits of any script, space and `_ - . ( ) , ' + & # @ ! = ~ [ ]`.
 * Everything else (in particular `/ \ : * ? " < > |` and control characters) is rejected, as are
 * empty names, names with `..`, names starting or ending with a dot or a space, and names that are
 * longer than [MAX_LENGTH] characters or [MAX_BYTES] UTF-8 bytes (the limit leaves room for a
 * ` (n)` suffix below the common file system limit of 255 bytes).
 */
object NameValidator {

    const val MAX_LENGTH = 100
    const val MAX_BYTES = 200

    private const val ALLOWED_PUNCTUATION = " _-.(),'+&#@!=~[]"

    /** Returns `null` if [name] is valid, otherwise the first problem found. */
    fun validate(name: String): NameProblem? {
        if (name.isEmpty()) return NameProblem.EMPTY
        if (name.length > MAX_LENGTH || name.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return NameProblem.TOO_LONG
        }
        if (name.any { it.isISOControl() }) return NameProblem.CONTROL_CHARACTER
        if (name.any { !(it.isLetterOrDigit() || it in ALLOWED_PUNCTUATION) }) {
            return NameProblem.FORBIDDEN_CHARACTER
        }
        if (name.isBlank()) return NameProblem.EMPTY
        if (name.first() == ' ' || name.last() == ' ') return NameProblem.LEADING_OR_TRAILING_SPACE
        if (".." in name || name.first() == '.' || name.last() == '.') return NameProblem.DOT_SEQUENCE
        return null
    }
}

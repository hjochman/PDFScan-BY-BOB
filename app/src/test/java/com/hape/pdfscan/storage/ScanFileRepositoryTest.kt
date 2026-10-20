package com.hape.pdfscan.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScanFileRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var repo: ScanFileRepository
    private val logged = mutableListOf<String>()

    @Before
    fun setUp() {
        root = tmp.newFolder("Scans").canonicalFile
        repo = ScanFileRepository(root, Dispatchers.Unconfined) { logged += it }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun <T> run(block: suspend () -> StorageResult<T>): StorageResult<T> = runBlocking { block() }

    private fun <T> ok(result: StorageResult<T>): T = when (result) {
        is StorageResult.Success -> result.value
        is StorageResult.Failure -> fail("Expected success but was ${result.error}").let { throw AssertionError() }
    }

    private fun assertError(expected: StorageError, result: StorageResult<*>) {
        assertEquals(StorageResult.Failure(expected), result)
    }

    private fun file(path: String, content: String = "x"): File =
        File(root, path).also { it.parentFile!!.mkdirs(); it.writeText(content) }

    private fun dir(path: String): File = File(root, path).also { it.mkdirs() }

    // ---- ensureRoot / list -------------------------------------------------------------------

    @Test
    fun ensureRootCreatesMissingScanFolder() {
        val missing = File(root, "deep/Scans")
        val r = ScanFileRepository(missing, Dispatchers.Unconfined)
        assertEquals(missing.canonicalFile, ok(run { r.ensureRoot() }))
        assertTrue(missing.isDirectory)
    }

    @Test
    fun listShowsFoldersFirstThenPdfsSortedAndSkipsOthers() {
        dir("beta")
        dir("Alpha")
        file("zeta.pdf")
        file("Apple.PDF")
        file("notes.txt")
        file(".hidden.pdf")
        dir(".hiddenDir")

        val names = ok(run { repo.list() }).map { it.name }
        assertEquals(listOf("Alpha", "beta", "Apple.PDF", "zeta.pdf"), names)
    }

    @Test
    fun listReportsTypeAndSize() {
        dir("sub")
        file("a.pdf", "12345")
        val entries = ok(run { repo.list() })
        assertTrue(entries[0].isDirectory)
        assertEquals(0L, entries[0].size)
        assertFalse(entries[1].isDirectory)
        assertEquals(5L, entries[1].size)
    }

    @Test
    fun listOfMissingOrOutsideFolderFails() {
        assertError(StorageError.NotFound, run { repo.list(File(root, "missing")) })
        assertError(StorageError.OutsideRoot, run { repo.list(tmp.newFolder("outside")) })
        assertError(StorageError.OutsideRoot, run { repo.list(File(root, "../Scans/../..")) })
    }

    @Test
    fun listOfAFileFailsWithNotFound() {
        assertError(StorageError.NotFound, run { repo.list(file("a.pdf")) })
    }

    // ---- createFolder ------------------------------------------------------------------------

    @Test
    fun createFolderCreatesNestedLevels() {
        var parent = root
        repeat(6) { level ->
            parent = ok(run { repo.createFolder(parent, "level$level") })
            assertTrue(parent.isDirectory)
        }
        assertEquals(File(root, "level0/level1/level2/level3/level4/level5").canonicalFile, parent)
    }

    @Test
    fun createFolderRejectsInvalidNames() {
        assertError(StorageError.NameInvalid(NameProblem.EMPTY), run { repo.createFolder(root, "") })
        assertError(StorageError.NameInvalid(NameProblem.DOT_SEQUENCE), run { repo.createFolder(root, "..") })
        assertError(
            StorageError.NameInvalid(NameProblem.FORBIDDEN_CHARACTER),
            run { repo.createFolder(root, "../escape") },
        )
        assertFalse(File(root.parentFile, "escape").exists())
    }

    @Test
    fun createFolderFailsIfNameExists() {
        dir("Taken")
        assertError(StorageError.AlreadyExists, run { repo.createFolder(root, "Taken") })
        file("File")
        assertError(StorageError.AlreadyExists, run { repo.createFolder(root, "File") })
    }

    @Test
    fun createFolderOutsideRootFails() {
        assertError(StorageError.OutsideRoot, run { repo.createFolder(tmp.newFolder("outside"), "x") })
        assertError(StorageError.OutsideRoot, run { repo.createFolder(File(root, ".."), "x") })
    }

    @Test
    fun createFolderInMissingParentFails() {
        assertError(StorageError.NotFound, run { repo.createFolder(File(root, "missing"), "x") })
    }

    // ---- rename ------------------------------------------------------------------------------

    @Test
    fun renameFile() {
        val f = file("a.pdf", "data")
        val renamed = ok(run { repo.rename(f, "b.pdf") })
        assertEquals(File(root, "b.pdf"), renamed)
        assertFalse(f.exists())
        assertEquals("data", renamed.readText())
    }

    @Test
    fun renameFolderKeepsContent() {
        file("old/inner/a.pdf", "data")
        ok(run { repo.rename(File(root, "old"), "new") })
        assertEquals("data", File(root, "new/inner/a.pdf").readText())
        assertFalse(File(root, "old").exists())
    }

    @Test
    fun renameRejectsInvalidAndExistingNames() {
        val f = file("a.pdf")
        file("b.pdf")
        assertError(StorageError.NameInvalid(NameProblem.FORBIDDEN_CHARACTER), run { repo.rename(f, "../x.pdf") })
        assertError(StorageError.NameInvalid(NameProblem.FORBIDDEN_CHARACTER), run { repo.rename(f, "sub/x.pdf") })
        assertError(StorageError.AlreadyExists, run { repo.rename(f, "b.pdf") })
        assertTrue(f.exists())
    }

    @Test
    fun renameToSameNameIsNoOp() {
        val f = file("a.pdf")
        assertEquals(f, ok(run { repo.rename(f, "a.pdf") }))
        assertTrue(f.exists())
    }

    @Test
    fun renameMissingOrOutsideFileFails() {
        assertError(StorageError.NotFound, run { repo.rename(File(root, "missing.pdf"), "x.pdf") })
        val outside = tmp.newFile("outside.pdf")
        assertError(StorageError.OutsideRoot, run { repo.rename(outside, "x.pdf") })
        assertTrue(outside.exists())
    }

    @Test
    fun scanFolderItselfCanNotBeRenamedOrDeleted() {
        assertError(StorageError.InvalidTarget, run { repo.rename(root, "other") })
        assertError(StorageError.InvalidTarget, run { repo.delete(root) })
        assertTrue(root.isDirectory)
    }

    // ---- delete ------------------------------------------------------------------------------

    @Test
    fun deleteFile() {
        val f = file("a.pdf")
        ok(run { repo.delete(f) })
        assertFalse(f.exists())
    }

    @Test
    fun deleteFolderRecursively() {
        file("d/a.pdf")
        file("d/e/f/g/b.pdf")
        dir("d/empty")
        file("keep.pdf")
        ok(run { repo.delete(File(root, "d")) })
        assertFalse(File(root, "d").exists())
        assertTrue(File(root, "keep.pdf").exists())
    }

    @Test
    fun deleteMissingOrOutsideFails() {
        assertError(StorageError.NotFound, run { repo.delete(File(root, "missing")) })
        val outside = tmp.newFile("outside.pdf")
        assertError(StorageError.OutsideRoot, run { repo.delete(outside) })
        assertError(StorageError.OutsideRoot, run { repo.delete(File(root, "../outside.pdf")) })
        assertTrue(outside.exists())
    }

    @Test
    fun deleteOfSymlinkRemovesOnlyTheLink() {
        val outside = tmp.newFolder("outside")
        val keep = File(outside, "keep.pdf").also { it.writeText("x") }
        val link = File(root, "link")
        assumeTrue(createSymlink(link, outside))
        ok(run { repo.delete(link) })
        assertFalse(java.nio.file.Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(keep.exists())
    }

    @Test
    fun deleteInsideFolderDoesNotFollowSymlinks() {
        val outside = tmp.newFolder("outside")
        val keep = File(outside, "keep.pdf").also { it.writeText("x") }
        dir("d")
        assumeTrue(createSymlink(File(root, "d/link"), outside))
        ok(run { repo.delete(File(root, "d")) })
        assertFalse(File(root, "d").exists())
        assertTrue(keep.exists())
    }

    // ---- copy --------------------------------------------------------------------------------

    @Test
    fun copyFileKeepsSource() {
        val f = file("a.pdf", "data")
        val target = dir("t")
        val copy = ok(run { repo.copy(f, target) })
        assertEquals(File(root, "t/a.pdf"), copy)
        assertEquals("data", copy.readText())
        assertTrue(f.exists())
    }

    @Test
    fun copyFolderRecursivelyThroughManyLevels() {
        file("src/a.pdf", "1")
        file("src/l1/l2/l3/l4/b.pdf", "2")
        dir("src/empty")
        val target = dir("t")
        ok(run { repo.copy(File(root, "src"), target) })
        assertEquals("1", File(root, "t/src/a.pdf").readText())
        assertEquals("2", File(root, "t/src/l1/l2/l3/l4/b.pdf").readText())
        assertTrue(File(root, "t/src/empty").isDirectory)
        assertTrue(File(root, "src/l1/l2/l3/l4/b.pdf").exists())
    }

    @Test
    fun copyFolderIntoItselfOrSubfolderFails() {
        file("src/sub/a.pdf")
        assertError(StorageError.InvalidTarget, run { repo.copy(File(root, "src"), File(root, "src")) })
        assertError(StorageError.InvalidTarget, run { repo.copy(File(root, "src"), File(root, "src/sub")) })
        assertFalse(File(root, "src/src").exists())
        assertFalse(File(root, "src/sub/src").exists())
    }

    @Test
    fun copyIntoSiblingWithSamePrefixIsAllowed() {
        file("src/a.pdf")
        val sibling = dir("src-2")
        ok(run { repo.copy(File(root, "src"), sibling) })
        assertTrue(File(root, "src-2/src/a.pdf").exists())
    }

    @Test
    fun copyDoesNotOverwriteByDefault() {
        val f = file("a.pdf", "new")
        file("t/a.pdf", "old")
        assertError(StorageError.AlreadyExists, run { repo.copy(f, File(root, "t")) })
        assertEquals("old", File(root, "t/a.pdf").readText())
    }

    @Test
    fun copyKeepBothUsesNumberedNames() {
        val f = file("a.pdf", "new")
        file("t/a.pdf", "old")
        val first = ok(run { repo.copy(f, File(root, "t"), ConflictStrategy.KEEP_BOTH) })
        val second = ok(run { repo.copy(f, File(root, "t"), ConflictStrategy.KEEP_BOTH) })
        assertEquals(File(root, "t/a (2).pdf"), first)
        assertEquals(File(root, "t/a (3).pdf"), second)
        assertEquals("old", File(root, "t/a.pdf").readText())
        assertEquals("new", first.readText())
    }

    @Test
    fun copyIntoSameFolderWithKeepBothCreatesSecondEntry() {
        val f = file("a.pdf")
        val copy = ok(run { repo.copy(f, root, ConflictStrategy.KEEP_BOTH) })
        assertEquals(File(root, "a (2).pdf"), copy)
        assertTrue(f.exists())
    }

    @Test
    fun copyFolderKeepBothRenamesFolderWithoutTreatingDotAsExtension() {
        file("v1.2/a.pdf")
        val copy = ok(run { repo.copy(File(root, "v1.2"), root, ConflictStrategy.KEEP_BOTH) })
        assertEquals(File(root, "v1.2 (2)"), copy)
        assertTrue(File(copy, "a.pdf").exists())
    }

    @Test
    fun copyToOutsideOrMissingTargetFails() {
        val f = file("a.pdf")
        assertError(StorageError.OutsideRoot, run { repo.copy(f, tmp.newFolder("outside")) })
        assertError(StorageError.NotFound, run { repo.copy(f, File(root, "missing")) })
        assertError(StorageError.NotFound, run { repo.copy(File(root, "missing.pdf"), root) })
        assertError(StorageError.OutsideRoot, run { repo.copy(tmp.newFile("o.pdf"), root) })
    }

    @Test
    fun copyIntoSymlinkPointingOutsideFails() {
        val outside = tmp.newFolder("outside")
        val link = File(root, "link")
        assumeTrue(createSymlink(link, outside))
        val f = file("a.pdf")
        assertError(StorageError.OutsideRoot, run { repo.copy(f, link) })
        assertEquals(0, outside.list()!!.size)
    }

    @Test
    fun copyOfFolderContainingSymlinkIsRejectedAndLeavesNothingBehind() {
        val outside = tmp.newFolder("outside")
        file("src/a.pdf")
        assumeTrue(createSymlink(File(root, "src/link"), outside))
        val target = dir("t")
        assertError(StorageError.SymlinkNotSupported, run { repo.copy(File(root, "src"), target) })
        assertFalse(File(root, "t/src").exists())
    }

    @Test
    fun copyOfSymlinkIsRejected() {
        val inside = dir("inside")
        val link = File(root, "link")
        assumeTrue(createSymlink(link, inside))
        val target = dir("t")
        assertError(StorageError.SymlinkNotSupported, run { repo.copy(link, target) })
    }

    // ---- move --------------------------------------------------------------------------------

    @Test
    fun moveFile() {
        val f = file("a.pdf", "data")
        val target = dir("t")
        val moved = ok(run { repo.move(f, target) })
        assertEquals(File(root, "t/a.pdf"), moved)
        assertEquals("data", moved.readText())
        assertFalse(f.exists())
    }

    @Test
    fun moveFolderWithContentBetweenLevels() {
        file("src/a.pdf", "1")
        file("src/x/y/b.pdf", "2")
        val target = dir("t/u/v")
        ok(run { repo.move(File(root, "src"), target) })
        assertEquals("1", File(root, "t/u/v/src/a.pdf").readText())
        assertEquals("2", File(root, "t/u/v/src/x/y/b.pdf").readText())
        assertFalse(File(root, "src").exists())
    }

    @Test
    fun moveFolderIntoItselfOrSubfolderFails() {
        file("src/sub/a.pdf")
        assertError(StorageError.InvalidTarget, run { repo.move(File(root, "src"), File(root, "src")) })
        assertError(StorageError.InvalidTarget, run { repo.move(File(root, "src"), File(root, "src/sub")) })
        assertTrue(File(root, "src/sub/a.pdf").exists())
    }

    @Test
    fun moveIntoSameFolderDoesNothing() {
        val f = file("a.pdf", "data")
        assertEquals(f, ok(run { repo.move(f, root) }))
        assertEquals("data", f.readText())
    }

    @Test
    fun moveDoesNotOverwrite() {
        val f = file("a.pdf", "new")
        file("t/a.pdf", "old")
        assertError(StorageError.AlreadyExists, run { repo.move(f, File(root, "t")) })
        assertEquals("old", File(root, "t/a.pdf").readText())
        assertTrue(f.exists())
    }

    @Test
    fun moveKeepBothUsesNumberedName() {
        val f = file("a.pdf", "new")
        file("t/a.pdf", "old")
        file("t/a (2).pdf", "old2")
        val moved = ok(run { repo.move(f, File(root, "t"), ConflictStrategy.KEEP_BOTH) })
        assertEquals(File(root, "t/a (3).pdf"), moved)
        assertFalse(f.exists())
    }

    @Test
    fun moveToOutsideFails() {
        val f = file("a.pdf")
        assertError(StorageError.OutsideRoot, run { repo.move(f, tmp.newFolder("outside")) })
        assertTrue(f.exists())
    }

    // ---- suggestUniqueName -------------------------------------------------------------------

    @Test
    fun suggestUniqueNameReturnsNameIfFree() {
        assertEquals("a.pdf", runBlocking { repo.suggestUniqueName(root, "a.pdf", false) })
    }

    @Test
    fun suggestUniqueNameAddsAndReplacesCounter() {
        file("a.pdf")
        assertEquals("a (2).pdf", runBlocking { repo.suggestUniqueName(root, "a.pdf", false) })
        file("a (2).pdf")
        assertEquals("a (3).pdf", runBlocking { repo.suggestUniqueName(root, "a.pdf", false) })
        assertEquals("a (3).pdf", runBlocking { repo.suggestUniqueName(root, "a (2).pdf", false) })
    }

    @Test
    fun suggestUniqueNameForNameWithoutExtension() {
        dir("Folder")
        assertEquals("Folder (2)", runBlocking { repo.suggestUniqueName(root, "Folder", true) })
    }

    // ---- logging -----------------------------------------------------------------------------

    @Test
    fun logMessagesContainNoPathsOrNames() {
        // Fails inside the copy: the source disappears between listing and copying is not reproducible,
        // so use an unreadable source folder if the platform allows it.
        val src = dir("secret-name/inner")
        assumeTrue(src.setReadable(false) && !src.canRead())
        try {
            run { repo.copy(File(root, "secret-name"), dir("t")) }
        } finally {
            src.setReadable(true)
        }
        logged.forEach {
            assertFalse(it, it.contains("secret-name"))
            assertFalse(it, it.contains(root.path))
        }
    }
}

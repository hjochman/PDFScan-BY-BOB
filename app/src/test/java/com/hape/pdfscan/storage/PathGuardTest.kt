package com.hape.pdfscan.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PathGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun rootAndDescendantsAreAccepted() {
        val root = tmp.newFolder("root")
        val sub = File(root, "a/b/c").also { it.mkdirs() }
        val guard = PathGuard(root)
        assertEquals(root.canonicalFile, guard.resolve(root))
        assertEquals(sub.canonicalFile, guard.resolve(sub))
        assertNotNull(guard.resolve(File(root, "does/not/exist.pdf")))
    }

    @Test
    fun traversalOutOfRootIsRejected() {
        val root = tmp.newFolder("root")
        tmp.newFolder("other")
        val guard = PathGuard(root)
        assertNull(guard.resolve(File(root, "../other")))
        assertNull(guard.resolve(File(root, "a/../../other")))
        assertNull(guard.resolve(root.parentFile!!))
    }

    @Test
    fun siblingWithSamePrefixIsRejected() {
        val root = tmp.newFolder("root")
        val sibling = tmp.newFolder("root-evil")
        assertNull(PathGuard(root).resolve(sibling))
    }

    @Test
    fun relativePathsAreRejected() {
        val root = tmp.newFolder("root")
        assertNull(PathGuard(root).resolve(File("relative/path")))
    }

    @Test
    fun symlinkPointingOutOfRootIsRejected() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        val link = File(root, "link")
        assumeTrue(createSymlink(link, outside))
        assertNull(PathGuard(root).resolve(link))
        assertNull(PathGuard(root).resolve(File(link, "file.pdf")))
    }

    @Test
    fun symlinkInsideRootIsAccepted() {
        val root = tmp.newFolder("root")
        val inside = File(root, "inside").also { it.mkdir() }
        val link = File(root, "link")
        assumeTrue(createSymlink(link, inside))
        assertEquals(inside.canonicalFile, PathGuard(root).resolve(link))
    }

    @Test
    fun isSameOrDescendantComparesPathComponents() {
        assertTrue(PathGuard.isSameOrDescendant(File("/a/b"), File("/a/b")))
        assertTrue(PathGuard.isSameOrDescendant(File("/a/b/c"), File("/a/b")))
        assertFalse(PathGuard.isSameOrDescendant(File("/a/bc"), File("/a/b")))
        assertFalse(PathGuard.isSameOrDescendant(File("/a"), File("/a/b")))
    }
}

/** Creates a symbolic link; returns `false` if the test system does not support that. */
internal fun createSymlink(link: File, target: File): Boolean = try {
    Files.createSymbolicLink(link.toPath(), target.toPath())
    true
} catch (_: Exception) {
    false
}

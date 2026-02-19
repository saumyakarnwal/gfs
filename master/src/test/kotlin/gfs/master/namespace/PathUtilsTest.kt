package gfs.master.namespace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathUtilsTest {

    @Test
    fun `validate accepts root`() {
        assertTrue(PathUtils.validate("/"))
    }

    @Test
    fun `validate accepts simple paths`() {
        assertTrue(PathUtils.validate("/a"))
        assertTrue(PathUtils.validate("/a/b"))
        assertTrue(PathUtils.validate("/a/b/c"))
    }

    @Test
    fun `validate rejects invalid paths`() {
        assertFalse(PathUtils.validate(""))
        assertFalse(PathUtils.validate("a"))
        assertFalse(PathUtils.validate("/a/"))
        assertFalse(PathUtils.validate("/a//b"))
    }

    @Test
    fun `parentPath returns parent`() {
        assertEquals("/", PathUtils.parentPath("/a"))
        assertEquals("/a", PathUtils.parentPath("/a/b"))
        assertEquals("/a/b", PathUtils.parentPath("/a/b/c"))
    }

    @Test
    fun `fileName returns last component`() {
        assertEquals("a", PathUtils.fileName("/a"))
        assertEquals("b", PathUtils.fileName("/a/b"))
        assertEquals("c", PathUtils.fileName("/a/b/c"))
    }

    @Test
    fun `components splits path`() {
        assertEquals(emptyList(), PathUtils.components("/"))
        assertEquals(listOf("a"), PathUtils.components("/a"))
        assertEquals(listOf("a", "b", "c"), PathUtils.components("/a/b/c"))
    }

    @Test
    fun `ancestorPaths returns all ancestors`() {
        assertEquals(listOf("/"), PathUtils.ancestorPaths("/a"))
        assertEquals(listOf("/", "/a"), PathUtils.ancestorPaths("/a/b"))
        assertEquals(listOf("/", "/a", "/a/b"), PathUtils.ancestorPaths("/a/b/c"))
    }

    @Test
    fun `isRoot identifies root`() {
        assertTrue(PathUtils.isRoot("/"))
        assertFalse(PathUtils.isRoot("/a"))
    }
}

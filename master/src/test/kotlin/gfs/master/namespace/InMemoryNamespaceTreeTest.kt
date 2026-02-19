package gfs.master.namespace

import gfs.proto.StatusCode
import kotlin.test.*

class InMemoryNamespaceTreeTest {

    private lateinit var tree: InMemoryNamespaceTree

    @BeforeTest
    fun setup() {
        tree = InMemoryNamespaceTree()
    }

    @Test
    fun `root exists by default`() {
        val root = tree.getNode("/")
        assertNotNull(root)
        assertTrue(root.isDirectory)
    }

    @Test
    fun `create file in root`() {
        val node = tree.createFile("/myfile", 3)
        assertEquals("/myfile", node.path)
        assertFalse(node.isDirectory)
        assertEquals(3, node.replicationFactor)
        assertTrue(tree.exists("/myfile"))
    }

    @Test
    fun `create directory and file inside it`() {
        tree.createDirectory("/data")
        tree.createFile("/data/file1.txt", 2)

        assertTrue(tree.exists("/data"))
        assertTrue(tree.exists("/data/file1.txt"))

        val children = tree.listDirectory("/data")
        assertEquals(1, children.size)
        assertEquals("/data/file1.txt", children[0].path)
    }

    @Test
    fun `create file fails when parent missing`() {
        val ex = assertFailsWith<NamespaceException> {
            tree.createFile("/nonexistent/file", 2)
        }
        assertEquals(StatusCode.NOT_FOUND, ex.statusCode)
    }

    @Test
    fun `create file fails when already exists`() {
        tree.createFile("/myfile", 2)
        val ex = assertFailsWith<NamespaceException> {
            tree.createFile("/myfile", 2)
        }
        assertEquals(StatusCode.ALREADY_EXISTS, ex.statusCode)
    }

    @Test
    fun `delete file`() {
        tree.createFile("/myfile", 2)
        tree.delete("/myfile")
        assertFalse(tree.exists("/myfile"))
    }

    @Test
    fun `delete non-empty directory fails`() {
        tree.createDirectory("/dir")
        tree.createFile("/dir/file", 2)
        assertFailsWith<NamespaceException> {
            tree.delete("/dir")
        }
    }

    @Test
    fun `delete empty directory succeeds`() {
        tree.createDirectory("/dir")
        tree.delete("/dir")
        assertFalse(tree.exists("/dir"))
    }

    @Test
    fun `rename file`() {
        tree.createFile("/old", 2)
        tree.rename("/old", "/new")
        assertFalse(tree.exists("/old"))
        assertTrue(tree.exists("/new"))
    }

    @Test
    fun `rename across directories`() {
        tree.createDirectory("/src")
        tree.createDirectory("/dst")
        tree.createFile("/src/file", 2)

        tree.rename("/src/file", "/dst/file")

        assertFalse(tree.exists("/src/file"))
        assertTrue(tree.exists("/dst/file"))
    }

    @Test
    fun `list directory returns direct children only`() {
        tree.createFile("/a", 2)
        tree.createDirectory("/b")
        tree.createFile("/c", 2)

        val entries = tree.listDirectory("/")
        assertEquals(3, entries.size)
        val names = entries.map { it.path }.toSet()
        assertEquals(setOf("/a", "/b", "/c"), names)
    }

    @Test
    fun `list directory does not return nested children`() {
        tree.createDirectory("/data")
        tree.createDirectory("/data/logs")
        tree.createFile("/data/logs/access.log", 2)
        tree.createFile("/data/config.txt", 2)

        val entries = tree.listDirectory("/data")
        val paths = entries.map { it.path }.toSet()
        assertEquals(setOf("/data/logs", "/data/config.txt"), paths)
    }

    @Test
    fun `add chunk handle to file`() {
        tree.createFile("/myfile", 2)
        tree.addChunkHandle("/myfile", 100L)
        tree.addChunkHandle("/myfile", 200L)

        val node = tree.getNode("/myfile")!!
        assertEquals(listOf(100L, 200L), node.chunkHandles)
    }

    @Test
    fun `replace chunk handle`() {
        tree.createFile("/myfile", 2)
        tree.addChunkHandle("/myfile", 100L)
        tree.addChunkHandle("/myfile", 200L)

        tree.replaceChunkHandle("/myfile", 0, 100L, 300L)

        val node = tree.getNode("/myfile")!!
        assertEquals(listOf(300L, 200L), node.chunkHandles)
    }

    @Test
    fun `getAllNodes and restoreFrom roundtrip`() {
        tree.createDirectory("/data")
        tree.createFile("/data/file", 2)
        tree.addChunkHandle("/data/file", 42L)

        val snapshot = tree.getAllNodes()
        val newTree = InMemoryNamespaceTree()
        newTree.restoreFrom(snapshot)

        assertTrue(newTree.exists("/"))
        assertTrue(newTree.exists("/data"))
        assertTrue(newTree.exists("/data/file"))
        assertEquals(listOf(42L), newTree.getNode("/data/file")!!.chunkHandles)
    }
}

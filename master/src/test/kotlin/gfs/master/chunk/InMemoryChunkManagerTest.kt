package gfs.master.chunk

import kotlin.test.*

class InMemoryChunkManagerTest {

    private lateinit var manager: InMemoryChunkManager

    @BeforeTest
    fun setup() {
        manager = InMemoryChunkManager()
    }

    @Test
    fun `allocate chunk returns unique handles`() {
        val c1 = manager.allocateChunk("/file", 0)
        val c2 = manager.allocateChunk("/file", 1)
        assertNotEquals(c1.handle, c2.handle)
        assertEquals(1L, c1.version)
        assertEquals(1L, c2.version)
    }

    @Test
    fun `get chunks for file`() {
        manager.allocateChunk("/file", 0)
        manager.allocateChunk("/file", 1)
        manager.allocateChunk("/other", 0)

        val chunks = manager.getChunksForFile("/file")
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[1].chunkIndex)
    }

    @Test
    fun `get chunk by handle`() {
        val chunk = manager.allocateChunk("/file", 0)
        val found = manager.getChunkMetadata(chunk.handle)
        assertNotNull(found)
        assertEquals("/file", found.filePath)
    }

    @Test
    fun `increment version`() {
        val chunk = manager.allocateChunk("/file", 0)
        val newVersion = manager.incrementVersion(chunk.handle)
        assertEquals(2L, newVersion)

        val updated = manager.getChunkMetadata(chunk.handle)!!
        assertEquals(2L, updated.version)
    }

    @Test
    fun `remove chunks for file`() {
        manager.allocateChunk("/file", 0)
        manager.allocateChunk("/file", 1)
        val removed = manager.removeChunksForFile("/file")
        assertEquals(2, removed.size)
        assertTrue(manager.getChunksForFile("/file").isEmpty())
    }

    @Test
    fun `restore from snapshot`() {
        val chunks = listOf(
            ChunkMetadata(10L, 1L, "/file", 0),
            ChunkMetadata(20L, 2L, "/file", 1)
        )
        manager.restoreFrom(chunks, 21L)

        assertEquals(21L, manager.getNextChunkHandle())
        assertEquals(2, manager.getChunksForFile("/file").size)
        assertNotNull(manager.getChunkMetadata(10L))
        assertNotNull(manager.getChunkMetadata(20L))
    }
}

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
    fun `allocated chunks have refCount 1`() {
        val chunk = manager.allocateChunk("/file", 0)
        assertEquals(1, chunk.referenceCount)
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
    fun `remove chunks for file decrements ref counts`() {
        val c1 = manager.allocateChunk("/file", 0)
        val c2 = manager.allocateChunk("/file", 1)

        val removed = manager.removeChunksForFile("/file")
        assertEquals(2, removed.size)
        assertTrue(manager.getChunksForFile("/file").isEmpty())
        // refCount was 1, decremented to 0, so chunks are removed from handle map
        assertNull(manager.getChunkMetadata(c1.handle))
        assertNull(manager.getChunkMetadata(c2.handle))
    }

    @Test
    fun `remove chunks preserves shared chunks`() {
        val chunk = manager.allocateChunk("/file", 0)
        manager.incrementRefCount(chunk.handle)
        manager.addChunkToFile("/snapshot", chunk.handle, 0)

        manager.removeChunksForFile("/file")

        // Chunk still exists because snapshot holds a reference (refCount went from 2 to 1)
        val meta = manager.getChunkMetadata(chunk.handle)
        assertNotNull(meta)
        assertEquals(1, meta.referenceCount)
    }

    @Test
    fun `increment ref count`() {
        val chunk = manager.allocateChunk("/file", 0)
        assertEquals(1, chunk.referenceCount)

        val newCount = manager.incrementRefCount(chunk.handle)
        assertEquals(2, newCount)

        val updated = manager.getChunkMetadata(chunk.handle)!!
        assertEquals(2, updated.referenceCount)
    }

    @Test
    fun `decrement ref count`() {
        val chunk = manager.allocateChunk("/file", 0)
        manager.incrementRefCount(chunk.handle) // now 2

        val newCount = manager.decrementRefCount(chunk.handle)
        assertEquals(1, newCount)
    }

    @Test
    fun `decrement ref count does not go below zero`() {
        val chunk = manager.allocateChunk("/file", 0)
        manager.decrementRefCount(chunk.handle) // 1 -> 0
        val count = manager.decrementRefCount(chunk.handle) // stays 0
        assertEquals(0, count)
    }

    @Test
    fun `add chunk to file`() {
        val chunk = manager.allocateChunk("/file", 0)
        manager.addChunkToFile("/snapshot", chunk.handle, 0)

        val snapshotChunks = manager.getChunksForFile("/snapshot")
        assertEquals(1, snapshotChunks.size)
        assertEquals(chunk.handle, snapshotChunks[0].handle)
    }

    @Test
    fun `replace chunk for file`() {
        val oldChunk = manager.allocateChunk("/file", 0)
        val newChunk = manager.allocateChunk("/file_cow", 0)

        manager.replaceChunkForFile("/file", 0, oldChunk.handle, newChunk.handle)

        val fileChunks = manager.getChunksForFile("/file")
        assertEquals(1, fileChunks.size)
        assertEquals(newChunk.handle, fileChunks[0].handle)
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

    @Test
    fun `restore preserves reference counts`() {
        val chunks = listOf(
            ChunkMetadata(10L, 1L, "/file", 0, referenceCount = 3)
        )
        manager.restoreFrom(chunks, 11L)

        val meta = manager.getChunkMetadata(10L)!!
        assertEquals(3, meta.referenceCount)
    }
}

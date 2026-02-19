package gfs.master.checkpoint

import gfs.master.chunk.ChunkMetadata
import gfs.master.namespace.NamespaceNode
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class FileCheckpointManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `save and load checkpoint`() {
        val manager = FileCheckpointManager(tempDir)

        val nodes = mapOf(
            "/" to NamespaceNode("/", true, 1000, 1000, 0),
            "/data" to NamespaceNode("/data", true, 1000, 1000, 0),
            "/data/file" to NamespaceNode("/data/file", false, 1000, 1000, 2, mutableListOf(42L))
        )
        val chunks = listOf(ChunkMetadata(42L, 1L, "/data/file", 0))

        val state = MasterState(nodes, chunks, 43L, 10L)
        manager.saveCheckpoint(state)

        val loaded = manager.loadLatestCheckpoint()
        assertNotNull(loaded)
        assertEquals(10L, loaded.lastSequenceNumber)
        assertEquals(43L, loaded.nextChunkHandle)
        assertEquals(3, loaded.nodes.size)
        assertEquals(1, loaded.chunks.size)
        assertEquals(42L, loaded.chunks[0].handle)
    }

    @Test
    fun `loads latest checkpoint when multiple exist`() {
        val manager = FileCheckpointManager(tempDir)

        val emptyNodes = mapOf("/" to NamespaceNode("/", true, 1000, 1000, 0))

        manager.saveCheckpoint(MasterState(emptyNodes, emptyList(), 1L, 5L))
        manager.saveCheckpoint(MasterState(emptyNodes, emptyList(), 1L, 15L))
        manager.saveCheckpoint(MasterState(emptyNodes, emptyList(), 1L, 10L))

        val loaded = manager.loadLatestCheckpoint()
        assertNotNull(loaded)
        assertEquals(15L, loaded.lastSequenceNumber)
    }

    @Test
    fun `returns null when no checkpoint exists`() {
        val manager = FileCheckpointManager(tempDir)
        assertNull(manager.loadLatestCheckpoint())
        assertNull(manager.getCheckpointSequenceNumber())
    }

    @Test
    fun `getCheckpointSequenceNumber returns latest`() {
        val manager = FileCheckpointManager(tempDir)
        val emptyNodes = mapOf("/" to NamespaceNode("/", true, 1000, 1000, 0))

        manager.saveCheckpoint(MasterState(emptyNodes, emptyList(), 1L, 100L))
        assertEquals(100L, manager.getCheckpointSequenceNumber())
    }
}

package gfs.master.getwritetarget

import gfs.master.chunk.InMemoryChunkManager
import gfs.master.chunkserver.InMemoryChunkServerRegistry
import gfs.master.lease.InMemoryLeaseManager
import gfs.master.namespace.InMemoryNamespaceTree
import gfs.master.oplog.OperationLog
import gfs.proto.*
import kotlin.test.*

class GetWriteTargetTest {

    private lateinit var namespaceTree: InMemoryNamespaceTree
    private lateinit var chunkManager: InMemoryChunkManager
    private lateinit var chunkServerRegistry: InMemoryChunkServerRegistry
    private lateinit var leaseManager: InMemoryLeaseManager
    private lateinit var operationLog: OperationLog
    private lateinit var getWriteTarget: GetWriteTarget

    @BeforeTest
    fun setup() {
        namespaceTree = InMemoryNamespaceTree()
        chunkManager = InMemoryChunkManager()
        chunkServerRegistry = InMemoryChunkServerRegistry()
        leaseManager = InMemoryLeaseManager()
        operationLog = NoOpOperationLog()
        getWriteTarget = GetWriteTarget(
            namespaceTree, chunkManager, chunkServerRegistry, leaseManager, operationLog
        )

        // Register a chunkserver so writes have a target
        chunkServerRegistry.register("cs-1", "localhost:50052", 1_000_000_000L)
    }

    @Test
    fun `write to nonexistent file returns NOT_FOUND`() {
        val resp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/nope")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )
        assertEquals(StatusCode.NOT_FOUND, resp.status.code)
    }

    @Test
    fun `first write allocates a chunk`() {
        namespaceTree.createFile("/file", 2)

        val resp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/file")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        assertEquals(StatusCode.OK, resp.status.code)
        assertTrue(resp.hasLease())

        val node = namespaceTree.getNode("/file")!!
        assertEquals(1, node.chunkHandles.size)
    }

    @Test
    fun `copy-on-write triggers for shared chunks`() {
        namespaceTree.createFile("/file", 2)
        val chunk = chunkManager.allocateChunk("/file", 0)
        namespaceTree.addChunkHandle("/file", chunk.handle)

        // Simulate snapshot: increment refCount
        chunkManager.incrementRefCount(chunk.handle)
        namespaceTree.createFile("/snap", 2)
        chunkManager.addChunkToFile("/snap", chunk.handle, 0)
        namespaceTree.addChunkHandle("/snap", chunk.handle)

        assertEquals(2, chunkManager.getChunkMetadata(chunk.handle)!!.referenceCount)

        // Write to original file should trigger copy-on-write
        val resp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/file")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        assertEquals(StatusCode.OK, resp.status.code)

        // Original file now has a different chunk handle
        val fileNode = namespaceTree.getNode("/file")!!
        assertNotEquals(chunk.handle, fileNode.chunkHandles[0])

        // Old chunk refCount decremented back to 1 (snapshot's reference)
        assertEquals(1, chunkManager.getChunkMetadata(chunk.handle)!!.referenceCount)

        // New chunk has refCount 1
        val newHandle = fileNode.chunkHandles[0]
        assertEquals(1, chunkManager.getChunkMetadata(newHandle)!!.referenceCount)
    }

    @Test
    fun `no copy-on-write for unshared chunks`() {
        namespaceTree.createFile("/file", 2)
        val chunk = chunkManager.allocateChunk("/file", 0)
        namespaceTree.addChunkHandle("/file", chunk.handle)

        // refCount is 1, no CoW needed
        chunkServerRegistry.updateHeartbeat("cs-1", setOf(chunk.handle), 1_000_000_000L)

        val resp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/file")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        assertEquals(StatusCode.OK, resp.status.code)

        // Same chunk handle — no copy-on-write
        val fileNode = namespaceTree.getNode("/file")!!
        assertEquals(chunk.handle, fileNode.chunkHandles[0])
    }
}

private class NoOpOperationLog : OperationLog {
    override fun append(entry: OperationLogEntry): Long = 0
    override fun readAll(): List<OperationLogEntry> = emptyList()
    override fun readFrom(sequenceNumber: Long): List<OperationLogEntry> = emptyList()
    override fun getLastSequenceNumber(): Long = 0
    override fun truncateBefore(sequenceNumber: Long) {}
    override fun flush() {}
    override fun close() {}
}

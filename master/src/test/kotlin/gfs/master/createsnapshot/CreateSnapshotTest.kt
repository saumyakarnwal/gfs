package gfs.master.createsnapshot

import gfs.master.chunk.InMemoryChunkManager
import gfs.master.namespace.InMemoryNamespaceTree
import gfs.master.oplog.OperationLog
import gfs.proto.*
import kotlin.test.*

class CreateSnapshotTest {

    private lateinit var namespaceTree: InMemoryNamespaceTree
    private lateinit var chunkManager: InMemoryChunkManager
    private lateinit var operationLog: OperationLog
    private lateinit var createSnapshot: CreateSnapshot

    @BeforeTest
    fun setup() {
        namespaceTree = InMemoryNamespaceTree()
        chunkManager = InMemoryChunkManager()
        operationLog = NoOpOperationLog()
        createSnapshot = CreateSnapshot(namespaceTree, chunkManager, operationLog)
    }

    @Test
    fun `snapshot creates dest file with same replication`() {
        namespaceTree.createFile("/source", 3)

        val resp = createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap")
                .build()
        )

        assertEquals(StatusCode.OK, resp.status.code)
        val snapNode = namespaceTree.getNode("/snap")
        assertNotNull(snapNode)
        assertFalse(snapNode.isDirectory)
        assertEquals(3, snapNode.replicationFactor)
    }

    @Test
    fun `snapshot shares chunk handles`() {
        namespaceTree.createFile("/source", 2)
        val c1 = chunkManager.allocateChunk("/source", 0)
        val c2 = chunkManager.allocateChunk("/source", 1)
        namespaceTree.addChunkHandle("/source", c1.handle)
        namespaceTree.addChunkHandle("/source", c2.handle)

        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap")
                .build()
        )

        val snapNode = namespaceTree.getNode("/snap")!!
        assertEquals(listOf(c1.handle, c2.handle), snapNode.chunkHandles)
    }

    @Test
    fun `snapshot increments ref counts`() {
        namespaceTree.createFile("/source", 2)
        val chunk = chunkManager.allocateChunk("/source", 0)
        namespaceTree.addChunkHandle("/source", chunk.handle)

        assertEquals(1, chunkManager.getChunkMetadata(chunk.handle)!!.referenceCount)

        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap")
                .build()
        )

        assertEquals(2, chunkManager.getChunkMetadata(chunk.handle)!!.referenceCount)
    }

    @Test
    fun `snapshot of nonexistent file returns NOT_FOUND`() {
        val resp = createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/nope")
                .setDestPath("/snap")
                .build()
        )
        assertEquals(StatusCode.NOT_FOUND, resp.status.code)
    }

    @Test
    fun `snapshot to existing path returns ALREADY_EXISTS`() {
        namespaceTree.createFile("/source", 2)
        namespaceTree.createFile("/dest", 2)

        val resp = createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/dest")
                .build()
        )
        assertEquals(StatusCode.ALREADY_EXISTS, resp.status.code)
    }

    @Test
    fun `snapshot of directory fails`() {
        namespaceTree.createDirectory("/dir")

        val ex = assertFailsWith<io.grpc.StatusException> {
            createSnapshot.execute(
                CreateSnapshotRequest.newBuilder()
                    .setSourcePath("/dir")
                    .setDestPath("/snap")
                    .build()
            )
        }
        assertEquals(io.grpc.Status.INVALID_ARGUMENT.code, ex.status.code)
    }

    @Test
    fun `multiple snapshots increment ref counts correctly`() {
        namespaceTree.createFile("/source", 2)
        val chunk = chunkManager.allocateChunk("/source", 0)
        namespaceTree.addChunkHandle("/source", chunk.handle)

        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap1")
                .build()
        )
        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap2")
                .build()
        )

        assertEquals(3, chunkManager.getChunkMetadata(chunk.handle)!!.referenceCount)
    }

    @Test
    fun `deleting source after snapshot preserves shared chunks`() {
        namespaceTree.createFile("/source", 2)
        val chunk = chunkManager.allocateChunk("/source", 0)
        namespaceTree.addChunkHandle("/source", chunk.handle)

        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/source")
                .setDestPath("/snap")
                .build()
        )

        // Remove source file's chunks (simulates what happens during deletion)
        chunkManager.removeChunksForFile("/source")
        namespaceTree.delete("/source")

        // Chunk still exists because snapshot holds a reference
        val meta = chunkManager.getChunkMetadata(chunk.handle)
        assertNotNull(meta)
        assertEquals(1, meta.referenceCount)
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

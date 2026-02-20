package gfs.master.integration

import gfs.master.chunk.InMemoryChunkManager
import gfs.master.chunkserver.InMemoryChunkServerRegistry
import gfs.master.createdirectory.CreateDirectory
import gfs.master.createfile.CreateFile
import gfs.master.createsnapshot.CreateSnapshot
import gfs.master.deletefile.DeleteFile
import gfs.master.gc.GarbageCollector
import gfs.master.getchunklocations.GetChunkLocations
import gfs.master.getfileinfo.GetFileInfo
import gfs.master.getwritetarget.GetWriteTarget
import gfs.master.lease.InMemoryLeaseManager
import gfs.master.listdirectory.ListDirectory
import gfs.master.namespace.InMemoryNamespaceTree
import gfs.master.oplog.OperationLog
import gfs.master.renamefile.RenameFile
import gfs.master.setreplication.SetReplication
import gfs.proto.*
import kotlin.test.*

class MasterIntegrationTest {

    private lateinit var namespaceTree: InMemoryNamespaceTree
    private lateinit var chunkManager: InMemoryChunkManager
    private lateinit var chunkServerRegistry: InMemoryChunkServerRegistry
    private lateinit var leaseManager: InMemoryLeaseManager
    private lateinit var operationLog: OperationLog
    private lateinit var garbageCollector: GarbageCollector

    private lateinit var createFile: CreateFile
    private lateinit var deleteFile: DeleteFile
    private lateinit var renameFile: RenameFile
    private lateinit var createDirectory: CreateDirectory
    private lateinit var listDirectory: ListDirectory
    private lateinit var getFileInfo: GetFileInfo
    private lateinit var getChunkLocations: GetChunkLocations
    private lateinit var getWriteTarget: GetWriteTarget
    private lateinit var createSnapshot: CreateSnapshot
    private lateinit var setReplication: SetReplication

    @BeforeTest
    fun setup() {
        namespaceTree = InMemoryNamespaceTree()
        chunkManager = InMemoryChunkManager()
        chunkServerRegistry = InMemoryChunkServerRegistry()
        leaseManager = InMemoryLeaseManager()
        operationLog = NoOpOperationLog()
        garbageCollector = GarbageCollector(namespaceTree, chunkManager)

        createFile = CreateFile(namespaceTree, operationLog)
        deleteFile = DeleteFile(namespaceTree, operationLog, garbageCollector)
        renameFile = RenameFile(namespaceTree, chunkManager, operationLog)
        createDirectory = CreateDirectory(namespaceTree, operationLog)
        listDirectory = ListDirectory(namespaceTree, chunkManager, chunkServerRegistry)
        getFileInfo = GetFileInfo(namespaceTree, chunkManager, chunkServerRegistry)
        getChunkLocations = GetChunkLocations(namespaceTree, chunkManager, chunkServerRegistry)
        getWriteTarget = GetWriteTarget(
            namespaceTree, chunkManager, chunkServerRegistry, leaseManager, operationLog
        )
        createSnapshot = CreateSnapshot(namespaceTree, chunkManager, operationLog)
        setReplication = SetReplication(namespaceTree, operationLog)

        // Register chunkservers
        chunkServerRegistry.register("cs-1", "localhost:50052", 1_000_000_000L)
        chunkServerRegistry.register("cs-2", "localhost:50053", 1_000_000_000L)
        chunkServerRegistry.register("cs-3", "localhost:50054", 1_000_000_000L)
    }

    @Test
    fun `full file lifecycle - create, write, read, delete`() {
        // Create directory
        val mkdirResp = createDirectory.execute(
            CreateDirectoryRequest.newBuilder().setPath("/data").build()
        )
        assertEquals(StatusCode.OK, mkdirResp.status.code)

        // Create file
        val createResp = createFile.execute(
            CreateFileRequest.newBuilder().setPath("/data/file.txt").setReplicationFactor(3).build()
        )
        assertEquals(StatusCode.OK, createResp.status.code)

        // Get write target (allocates chunk)
        val writeResp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/data/file.txt")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )
        assertEquals(StatusCode.OK, writeResp.status.code)
        assertTrue(writeResp.hasLease())
        assertNotEquals(0, writeResp.lease.chunk.handle)

        // Verify file has chunks
        val infoResp = getFileInfo.execute(
            GetFileInfoRequest.newBuilder().setPath("/data/file.txt").build()
        )
        assertEquals(StatusCode.OK, infoResp.status.code)
        assertEquals(1, infoResp.fileInfo.chunksCount)
        assertEquals(3, infoResp.fileInfo.replicationFactor)

        // List directory
        val listResp = listDirectory.execute(
            ListDirectoryRequest.newBuilder().setPath("/data").build()
        )
        assertEquals(StatusCode.OK, listResp.status.code)
        assertEquals(1, listResp.entriesCount)
        assertEquals("/data/file.txt", listResp.entriesList[0].path)

        // Delete file
        val deleteResp = deleteFile.execute(
            DeleteFileRequest.newBuilder().setPath("/data/file.txt").build()
        )
        assertEquals(StatusCode.OK, deleteResp.status.code)

        // File is lazily deleted (hidden file created)
        assertFalse(namespaceTree.exists("/data/file.txt"))
    }

    @Test
    fun `snapshot and copy-on-write flow`() {
        // Setup: create file with a chunk
        createFile.execute(
            CreateFileRequest.newBuilder().setPath("/original").setReplicationFactor(2).build()
        )
        val writeResp = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/original")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )
        assertEquals(StatusCode.OK, writeResp.status.code)

        val originalHandle = namespaceTree.getNode("/original")!!.chunkHandles[0]

        // Snapshot
        val snapResp = createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/original")
                .setDestPath("/snapshot")
                .build()
        )
        assertEquals(StatusCode.OK, snapResp.status.code)

        // Both files share the chunk, refCount = 2
        val snapNode = namespaceTree.getNode("/snapshot")!!
        assertEquals(originalHandle, snapNode.chunkHandles[0])
        assertEquals(2, chunkManager.getChunkMetadata(originalHandle)!!.referenceCount)

        // Write to original triggers copy-on-write
        val writeResp2 = getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/original")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )
        assertEquals(StatusCode.OK, writeResp2.status.code)

        // Original now has a new chunk
        val newHandle = namespaceTree.getNode("/original")!!.chunkHandles[0]
        assertNotEquals(originalHandle, newHandle)

        // Old chunk refCount back to 1 (only snapshot)
        assertEquals(1, chunkManager.getChunkMetadata(originalHandle)!!.referenceCount)

        // New chunk refCount is 1
        assertEquals(1, chunkManager.getChunkMetadata(newHandle)!!.referenceCount)

        // Snapshot still has the original chunk
        assertEquals(originalHandle, namespaceTree.getNode("/snapshot")!!.chunkHandles[0])
    }

    @Test
    fun `rename preserves chunk handles`() {
        createFile.execute(
            CreateFileRequest.newBuilder().setPath("/old").setReplicationFactor(2).build()
        )
        getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/old")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        val handle = namespaceTree.getNode("/old")!!.chunkHandles[0]

        renameFile.execute(
            RenameFileRequest.newBuilder()
                .setSourcePath("/old")
                .setDestPath("/new")
                .build()
        )

        val newNode = namespaceTree.getNode("/new")!!
        assertEquals(handle, newNode.chunkHandles[0])
    }

    @Test
    fun `set replication changes factor`() {
        createFile.execute(
            CreateFileRequest.newBuilder().setPath("/file").setReplicationFactor(2).build()
        )

        val resp = setReplication.execute(
            SetReplicationRequest.newBuilder()
                .setPath("/file")
                .setReplicationFactor(5)
                .build()
        )

        assertEquals(StatusCode.OK, resp.status.code)
        assertEquals(2, resp.previousReplicationFactor)

        val node = namespaceTree.getNode("/file")!!
        assertEquals(5, node.replicationFactor)
    }

    @Test
    fun `garbage collector cleans hidden files`() {
        createFile.execute(
            CreateFileRequest.newBuilder().setPath("/temp").setReplicationFactor(2).build()
        )
        getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/temp")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        val handle = namespaceTree.getNode("/temp")!!.chunkHandles[0]

        // Delete file (moves to hidden)
        deleteFile.execute(
            DeleteFileRequest.newBuilder().setPath("/temp").build()
        )

        // Hidden file exists
        val hiddenFiles = namespaceTree.getAllNodes().keys.filter {
            it.startsWith(GarbageCollector.HIDDEN_PREFIX)
        }
        assertEquals(1, hiddenFiles.size)

        // Chunk still exists (hidden file holds reference)
        assertNotNull(chunkManager.getChunkMetadata(handle))

        // Force all hidden files to be "expired" by modifying timestamp
        for (path in hiddenFiles) {
            val node = namespaceTree.getNode(path)!!
            node.modifiedAt = 0 // very old
        }

        garbageCollector.collectGarbage()

        // Hidden file and chunk removed
        assertTrue(namespaceTree.getAllNodes().keys.none { it.startsWith(GarbageCollector.HIDDEN_PREFIX) })
        assertNull(chunkManager.getChunkMetadata(handle))
    }

    @Test
    fun `gc preserves shared chunks during deletion`() {
        // Create file and snapshot
        createFile.execute(
            CreateFileRequest.newBuilder().setPath("/file").setReplicationFactor(2).build()
        )
        getWriteTarget.execute(
            GetWriteTargetRequest.newBuilder()
                .setPath("/file")
                .setOffset(0)
                .setMutationType(MutationType.WRITE)
                .build()
        )

        val handle = namespaceTree.getNode("/file")!!.chunkHandles[0]

        createSnapshot.execute(
            CreateSnapshotRequest.newBuilder()
                .setSourcePath("/file")
                .setDestPath("/snap")
                .build()
        )

        assertEquals(2, chunkManager.getChunkMetadata(handle)!!.referenceCount)

        // Delete original
        deleteFile.execute(
            DeleteFileRequest.newBuilder().setPath("/file").build()
        )

        // Force GC
        for ((path, node) in namespaceTree.getAllNodes()) {
            if (path.startsWith(GarbageCollector.HIDDEN_PREFIX)) {
                node.modifiedAt = 0
            }
        }
        garbageCollector.collectGarbage()

        // Chunk still exists because snapshot holds a reference
        val meta = chunkManager.getChunkMetadata(handle)
        assertNotNull(meta)
        assertTrue(meta.referenceCount >= 1)
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

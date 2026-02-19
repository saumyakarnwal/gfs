package gfs.master

import gfs.common.GfsConfig
import gfs.master.checkpoint.CheckpointManager
import gfs.master.checkpoint.FileCheckpointManager
import gfs.master.checkpoint.MasterState
import gfs.master.chunk.ChunkManager
import gfs.master.chunk.InMemoryChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.chunkserver.InMemoryChunkServerRegistry
import gfs.master.createfile.CreateFile
import gfs.master.createdirectory.CreateDirectory
import gfs.master.createsnapshot.CreateSnapshot
import gfs.master.deletefile.DeleteFile
import gfs.master.failuredetector.FailureDetector
import gfs.master.gc.GarbageCollector
import gfs.master.getfileinfo.GetFileInfo
import gfs.master.getchunklocations.GetChunkLocations
import gfs.master.getwritetarget.GetWriteTarget
import gfs.master.lease.InMemoryLeaseManager
import gfs.master.lease.LeaseManager
import gfs.master.listdirectory.ListDirectory
import gfs.master.namespace.InMemoryNamespaceTree
import gfs.master.namespace.NamespaceTree
import gfs.master.oplog.FileOperationLog
import gfs.master.oplog.OperationLog
import gfs.master.renamefile.RenameFile
import gfs.master.setreplication.SetReplication
import gfs.master.service.MasterChunkServerService
import gfs.master.service.MasterService
import gfs.proto.OperationType
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.util.logging.Logger

class MasterServer(
    private val dataDir: Path = Path.of(GfsConfig.MASTER_DATA_DIR),
    private val port: Int = GfsConfig.MASTER_PORT
) {
    private val logger = Logger.getLogger(MasterServer::class.java.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var namespaceTree: NamespaceTree
    private lateinit var chunkManager: ChunkManager
    private lateinit var operationLog: OperationLog
    private lateinit var checkpointManager: CheckpointManager
    private lateinit var chunkServerRegistry: ChunkServerRegistry
    private lateinit var leaseManager: LeaseManager
    private lateinit var failureDetector: FailureDetector
    private lateinit var garbageCollector: GarbageCollector
    private lateinit var grpcServer: Server

    fun start() {
        logger.info("Starting master server on port $port")

        // Create domain components
        namespaceTree = InMemoryNamespaceTree()
        chunkManager = InMemoryChunkManager()
        operationLog = FileOperationLog(dataDir.resolve("oplog"))
        checkpointManager = FileCheckpointManager(dataDir.resolve("checkpoints"))
        chunkServerRegistry = InMemoryChunkServerRegistry()
        leaseManager = InMemoryLeaseManager()
        garbageCollector = GarbageCollector(namespaceTree, chunkManager)

        failureDetector = FailureDetector(
            chunkServerRegistry, chunkManager, namespaceTree
        ) { chunkHandle, deficit ->
            logger.info("Re-replication needed: chunk $chunkHandle, deficit=$deficit")
            // Instruct chunkservers via next heartbeat response
        }

        // Recover state
        recover()

        // Wire RPC handlers
        val createFile = CreateFile(namespaceTree, operationLog)
        val deleteFile = DeleteFile(namespaceTree, operationLog, garbageCollector)
        val renameFile = RenameFile(namespaceTree, operationLog)
        val createDirectory = CreateDirectory(namespaceTree, operationLog)
        val listDirectory = ListDirectory(namespaceTree, chunkManager, chunkServerRegistry)
        val getFileInfo = GetFileInfo(namespaceTree, chunkManager, chunkServerRegistry)
        val getChunkLocations = GetChunkLocations(namespaceTree, chunkManager, chunkServerRegistry)
        val getWriteTarget = GetWriteTarget(
            namespaceTree, chunkManager, chunkServerRegistry, leaseManager, operationLog
        )
        val createSnapshot = CreateSnapshot(namespaceTree, chunkManager, operationLog)
        val setReplication = SetReplication(namespaceTree, operationLog)

        val masterService = MasterService(
            createFile, deleteFile, renameFile, createDirectory,
            listDirectory, getFileInfo, getChunkLocations,
            getWriteTarget, createSnapshot, setReplication
        )
        val chunkServerService = MasterChunkServerService(chunkServerRegistry, leaseManager)

        // Build and start gRPC server
        grpcServer = ServerBuilder.forPort(port)
            .addService(masterService)
            .addService(chunkServerService)
            .build()
            .start()

        // Start background tasks
        failureDetector.start(scope)
        garbageCollector.start(scope)

        logger.info("Master server started on port $port")

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down master server")
            stop()
        })
    }

    fun stop() {
        if (::failureDetector.isInitialized) failureDetector.stop()
        if (::garbageCollector.isInitialized) garbageCollector.stop()
        if (::grpcServer.isInitialized) grpcServer.shutdown()
        if (::operationLog.isInitialized) operationLog.close()
    }

    fun blockUntilShutdown() {
        grpcServer.awaitTermination()
    }

    private fun recover() {
        val checkpoint = checkpointManager.loadLatestCheckpoint()
        if (checkpoint != null) {
            logger.info("Restoring from checkpoint at sequence ${checkpoint.lastSequenceNumber}")
            namespaceTree.restoreFrom(checkpoint.nodes)
            chunkManager.restoreFrom(checkpoint.chunks, checkpoint.nextChunkHandle)

            val entries = operationLog.readFrom(checkpoint.lastSequenceNumber + 1)
            logger.info("Replaying ${entries.size} oplog entries after checkpoint")
            entries.forEach { replayEntry(it) }
        } else {
            val entries = operationLog.readAll()
            if (entries.isNotEmpty()) {
                logger.info("No checkpoint found; replaying ${entries.size} oplog entries")
                entries.forEach { replayEntry(it) }
            } else {
                logger.info("Clean start — no checkpoint or oplog entries")
            }
        }
    }

    private fun replayEntry(entry: gfs.proto.OperationLogEntry) {
        try {
            when (entry.type) {
                OperationType.OP_CREATE_FILE -> {
                    val op = entry.createFile
                    namespaceTree.createFile(op.path, op.replicationFactor)
                }
                OperationType.OP_DELETE_FILE -> {
                    val path = entry.deleteFile.path
                    try {
                        namespaceTree.delete(path)
                    } catch (_: Exception) {
                        // File may have already been lazily moved
                    }
                    chunkManager.removeChunksForFile(path)
                }
                OperationType.OP_CREATE_DIRECTORY -> {
                    namespaceTree.createDirectory(entry.createDirectory.path)
                }
                OperationType.OP_RENAME_FILE -> {
                    val op = entry.renameFile
                    namespaceTree.rename(op.sourcePath, op.destPath)
                }
                OperationType.OP_ALLOCATE_CHUNK -> {
                    val op = entry.allocateChunk
                    chunkManager.allocateChunk(op.path, op.chunkIndex.toInt())
                    namespaceTree.addChunkHandle(op.path, op.chunk.handle)
                }
                OperationType.OP_SET_REPLICATION -> {
                    val op = entry.setReplication
                    namespaceTree.setReplicationFactor(op.path, op.replicationFactor)
                }
                OperationType.OP_CREATE_SNAPSHOT -> {
                    val op = entry.createSnapshot
                    val sourceNode = namespaceTree.getNode(op.sourcePath) ?: return
                    namespaceTree.createFile(op.destPath, sourceNode.replicationFactor)
                    for (handle in sourceNode.chunkHandles) {
                        val meta = chunkManager.getChunkMetadata(handle) ?: continue
                        chunkManager.incrementRefCount(handle)
                        chunkManager.addChunkToFile(op.destPath, handle, meta.chunkIndex)
                        namespaceTree.addChunkHandle(op.destPath, handle)
                    }
                }
                OperationType.OP_COPY_ON_WRITE -> {
                    val op = entry.copyOnWrite
                    chunkManager.allocateChunk(op.path, op.chunkIndex)
                    namespaceTree.replaceChunkHandle(op.path, op.chunkIndex, op.oldHandle, op.newHandle)
                    chunkManager.replaceChunkForFile(op.path, op.chunkIndex, op.oldHandle, op.newHandle)
                    chunkManager.decrementRefCount(op.oldHandle)
                }
                else -> {
                    logger.warning("Unknown operation type: ${entry.type}")
                }
            }
        } catch (e: Exception) {
            logger.fine("Replay entry ${entry.sequenceNumber} skipped: ${e.message}")
        }
    }

    fun triggerCheckpoint() {
        val state = MasterState(
            nodes = namespaceTree.getAllNodes(),
            chunks = chunkManager.getAllChunks().toList(),
            nextChunkHandle = chunkManager.getNextChunkHandle(),
            lastSequenceNumber = operationLog.getLastSequenceNumber()
        )
        checkpointManager.saveCheckpoint(state)
        operationLog.truncateBefore(state.lastSequenceNumber)
        logger.info("Checkpoint saved at sequence ${state.lastSequenceNumber}")
    }
}

fun main() {
    val server = MasterServer()
    server.start()
    server.blockUntilShutdown()
}

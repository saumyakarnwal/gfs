package gfs.chunkserver.heartbeat

import gfs.chunkserver.storage.ChunkStorage
import gfs.common.GfsConfig
import gfs.proto.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.*
import java.util.logging.Level
import java.util.logging.Logger

class HeartbeatSender(
    private val serverId: String,
    private val endpoint: String,
    private val chunkStorage: ChunkStorage,
    private val masterHost: String = GfsConfig.MASTER_HOST,
    private val masterPort: Int = GfsConfig.MASTER_PORT
) {
    private val logger = Logger.getLogger(HeartbeatSender::class.java.name)

    private var channel: ManagedChannel? = null
    private var stub: MasterChunkServerServiceGrpcKt.MasterChunkServerServiceCoroutineStub? = null
    private var heartbeatJob: Job? = null
    private var registered = false

    fun start(scope: CoroutineScope) {
        channel = ManagedChannelBuilder
            .forAddress(masterHost, masterPort)
            .usePlaintext()
            .build()
        stub = MasterChunkServerServiceGrpcKt.MasterChunkServerServiceCoroutineStub(channel!!)

        heartbeatJob = scope.launch {
            registerWithMaster()
            while (isActive) {
                delay(GfsConfig.HEARTBEAT_INTERVAL_MS)
                sendHeartbeat()
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        channel?.shutdown()
    }

    private suspend fun registerWithMaster() {
        val chunkReports = buildChunkReports()
        val request = RegisterChunkServerRequest.newBuilder()
            .setChunkserver(chunkServerAddress())
            .addAllChunks(chunkReports)
            .setAvailableBytes(availableBytes())
            .build()

        try {
            stub!!.registerChunkServer(request)
            registered = true
            logger.info("Registered with master as $serverId")
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to register with master", e)
        }
    }

    private suspend fun sendHeartbeat() {
        val chunkReports = buildChunkReports()
        val request = SendHeartbeatRequest.newBuilder()
            .setChunkserver(chunkServerAddress())
            .addAllChunks(chunkReports)
            .setIsFullReport(true)
            .setAvailableBytes(availableBytes())
            .build()

        try {
            val response = stub!!.sendHeartbeat(request)
            handleInstructions(response.instructionsList)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Heartbeat failed, will re-register", e)
            registered = false
            registerWithMaster()
        }
    }

    private fun handleInstructions(instructions: List<ChunkServerInstruction>) {
        for (instruction in instructions) {
            when {
                instruction.hasDeleteChunk() -> {
                    val handle = instruction.deleteChunk.chunk.handle
                    logger.info("Master instructed: delete chunk $handle")
                    chunkStorage.deleteChunk(handle)
                }
                instruction.hasInvalidateChunk() -> {
                    val handle = instruction.invalidateChunk.chunk.handle
                    logger.info("Master instructed: invalidate chunk $handle")
                    chunkStorage.deleteChunk(handle)
                }
                instruction.hasReReplicate() -> {
                    // Phase 5: re-replication
                    logger.info("Master instructed: re-replicate chunk ${instruction.reReplicate.chunk.handle}")
                }
            }
        }
    }

    private fun buildChunkReports(): List<ChunkReport> {
        return chunkStorage.getAllChunkHandles().mapNotNull { handle ->
            val info = chunkStorage.getChunkInfo(handle) ?: return@mapNotNull null
            ChunkReport.newBuilder()
                .setChunk(
                    ChunkReference.newBuilder()
                        .setHandle(handle)
                        .setVersion(info.version)
                )
                .setSizeBytes(info.sizeBytes)
                .build()
        }
    }

    private fun chunkServerAddress(): ChunkServerAddress =
        ChunkServerAddress.newBuilder()
            .setServerId(serverId)
            .setEndpoint(endpoint)
            .build()

    private fun availableBytes(): Long {
        val store = java.io.File(GfsConfig.DATA_ROOT)
        return if (store.exists()) store.usableSpace else 0L
    }
}

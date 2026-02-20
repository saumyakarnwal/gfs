package gfs.chunkserver

import gfs.chunkserver.applymutation.ApplyMutation
import gfs.chunkserver.commitmutation.CommitMutation
import gfs.chunkserver.copychunk.CopyChunk
import gfs.chunkserver.createchunk.CreateChunk
import gfs.chunkserver.deletechunk.DeleteChunk
import gfs.chunkserver.heartbeat.HeartbeatSender
import gfs.chunkserver.pushdata.PushData
import gfs.chunkserver.readchunk.ReadChunk
import gfs.chunkserver.updatechunkversion.UpdateChunkVersion
import gfs.chunkserver.service.ChunkServerGrpcService
import gfs.chunkserver.storage.DiskChunkStorage
import gfs.common.GfsConfig
import io.grpc.Server
import io.grpc.ServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Path
import java.util.logging.Logger

class ChunkServer(
    private val serverIndex: Int = 0,
    private val port: Int = GfsConfig.chunkserverPort(serverIndex),
    private val dataDir: Path = Path.of(GfsConfig.chunkserverDataDir(port))
) {
    private val logger = Logger.getLogger(ChunkServer::class.java.name)
    private val serverId = "cs-$port"
    private val endpoint = "${GfsConfig.MASTER_HOST}:$port"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var grpcServer: Server
    private lateinit var heartbeatSender: HeartbeatSender

    fun start() {
        logger.info("Starting chunkserver $serverId on port $port, data=$dataDir")

        val chunkStorage = DiskChunkStorage(dataDir)

        // Wire RPC handlers
        val readChunk = ReadChunk(chunkStorage)
        val pushData = PushData()
        val commitMutation = CommitMutation(chunkStorage, pushData, serverId)
        val applyMutation = ApplyMutation(chunkStorage)
        val createChunk = CreateChunk(chunkStorage)
        val deleteChunk = DeleteChunk(chunkStorage)
        val copyChunk = CopyChunk(chunkStorage)
        val updateChunkVersion = UpdateChunkVersion(chunkStorage)

        val service = ChunkServerGrpcService(
            readChunk, pushData, commitMutation, applyMutation,
            createChunk, deleteChunk, copyChunk, updateChunkVersion
        )

        grpcServer = ServerBuilder.forPort(port)
            .addService(service)
            .build()
            .start()

        logger.info("ChunkServer $serverId gRPC started on port $port")

        // Start heartbeat after gRPC is ready
        heartbeatSender = HeartbeatSender(serverId, endpoint, chunkStorage)
        heartbeatSender.start(scope)

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down chunkserver $serverId")
            stop()
        })
    }

    fun stop() {
        if (::heartbeatSender.isInitialized) {
            heartbeatSender.stop()
        }
        if (::grpcServer.isInitialized) {
            grpcServer.shutdown()
        }
    }

    fun blockUntilShutdown() {
        grpcServer.awaitTermination()
    }
}

fun main(args: Array<String>) {
    val index = args.firstOrNull()?.toIntOrNull() ?: 0
    val server = ChunkServer(serverIndex = index)
    server.start()
    server.blockUntilShutdown()
}

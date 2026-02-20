package gfs.master.service

import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.lease.LeaseManager
import gfs.proto.*
import java.util.logging.Logger

class MasterChunkServerService(
    private val chunkServerRegistry: ChunkServerRegistry,
    private val leaseManager: LeaseManager,
    private val chunkManager: ChunkManager
) : MasterChunkServerServiceGrpcKt.MasterChunkServerServiceCoroutineImplBase() {

    private val logger = Logger.getLogger(MasterChunkServerService::class.java.name)

    override suspend fun registerChunkServer(request: RegisterChunkServerRequest) = methodImpl {
        val cs = request.chunkserver
        chunkServerRegistry.register(cs.serverId, cs.endpoint, request.availableBytes)

        val chunkHandles = request.chunksList.map { it.chunk.handle }.toSet()
        chunkServerRegistry.updateHeartbeat(cs.serverId, chunkHandles, request.availableBytes)

        RegisterChunkServerResponse.newBuilder()
            .setStatus(okStatus("Registered: ${cs.serverId}"))
            .build()
    }

    override suspend fun sendHeartbeat(request: SendHeartbeatRequest) = methodImpl {
        val cs = request.chunkserver
        val chunkHandles = request.chunksList.map { it.chunk.handle }.toSet()

        // Auto-register if master doesn't know this server (e.g. after master restart)
        if (chunkServerRegistry.getServerInfo(cs.serverId) == null) {
            logger.info("Auto-registering unknown chunkserver ${cs.serverId} from heartbeat")
            chunkServerRegistry.register(cs.serverId, cs.endpoint, request.availableBytes)
        }

        chunkServerRegistry.updateHeartbeat(cs.serverId, chunkHandles, request.availableBytes)

        // Find chunks the chunkserver reports but the master doesn't know about
        val unknownChunks = chunkHandles.filter { chunkManager.getChunkMetadata(it) == null }

        val instructions = unknownChunks.map { handle ->
            logger.info("Instructing ${cs.serverId} to delete unknown chunk $handle")
            ChunkServerInstruction.newBuilder()
                .setDeleteChunk(
                    DeleteChunkInstruction.newBuilder()
                        .setChunk(ChunkReference.newBuilder().setHandle(handle))
                )
                .build()
        }

        SendHeartbeatResponse.newBuilder()
            .addAllInstructions(instructions)
            .build()
    }

    override suspend fun extendLease(request: ExtendLeaseRequest) = methodImpl {
        val chunkHandle = request.chunk.handle
        val serverId = request.chunkserver.serverId

        val extended = leaseManager.extendLease(chunkHandle, serverId)
        if (extended != null) {
            ExtendLeaseResponse.newBuilder()
                .setStatus(okStatus("Lease extended"))
                .setNewExpirationTimestamp(extended.expirationTimestamp)
                .build()
        } else {
            ExtendLeaseResponse.newBuilder()
                .setStatus(status(StatusCode.LEASE_EXPIRED, "No valid lease to extend"))
                .build()
        }
    }
}

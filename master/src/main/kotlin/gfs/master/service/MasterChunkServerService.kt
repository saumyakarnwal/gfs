package gfs.master.service

import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.lease.LeaseManager
import gfs.proto.*

class MasterChunkServerService(
    private val chunkServerRegistry: ChunkServerRegistry,
    private val leaseManager: LeaseManager
) : MasterChunkServerServiceGrpcKt.MasterChunkServerServiceCoroutineImplBase() {

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
        chunkServerRegistry.updateHeartbeat(cs.serverId, chunkHandles, request.availableBytes)

        SendHeartbeatResponse.newBuilder().build()
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

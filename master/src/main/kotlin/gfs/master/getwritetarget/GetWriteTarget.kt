package gfs.master.getwritetarget

import gfs.common.GfsConfig
import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.lease.LeaseManager
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class GetWriteTarget(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val chunkServerRegistry: ChunkServerRegistry,
    private val leaseManager: LeaseManager,
    private val operationLog: OperationLog
) {

    fun execute(request: GetWriteTargetRequest): GetWriteTargetResponse {
        val path = request.path
        val offset = request.offset
        val mutationType = request.mutationType

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }

        val node = namespaceTree.getNode(path)
            ?: return GetWriteTargetResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "File not found: $path"))
                .build()

        checkRequest(!node.isDirectory) { "Cannot write to directory: $path" }

        val chunkIndex = when (mutationType) {
            MutationType.RECORD_APPEND -> {
                // Append goes to the last chunk, or creates the first one
                if (node.chunkHandles.isEmpty()) 0 else node.chunkHandles.size - 1
            }
            else -> (offset / GfsConfig.CHUNK_SIZE_BYTES).toInt()
        }

        // Allocate chunk if it doesn't exist yet
        val chunkHandle = if (chunkIndex >= node.chunkHandles.size) {
            val chunk = allocateNewChunk(path, chunkIndex)
            chunk.handle
        } else {
            node.chunkHandles[chunkIndex]
        }

        val chunkMeta = chunkManager.getChunkMetadata(chunkHandle)
            ?: return GetWriteTargetResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Chunk metadata missing for handle $chunkHandle"))
                .build()

        // Find chunkservers that hold this chunk
        val locations = chunkServerRegistry.getLocationsForChunk(chunkHandle)
        if (locations.isEmpty()) {
            // No chunkservers have this chunk yet — select servers and create the chunk
            val selectedServers = chunkServerRegistry.selectServersForNewChunk(node.replicationFactor)
            if (selectedServers.isEmpty()) {
                return GetWriteTargetResponse.newBuilder()
                    .setStatus(status(StatusCode.INTERNAL_ERROR, "No chunkservers available"))
                    .build()
            }

            // Grant lease and return — the client will instruct chunkservers to create the chunk
            val lease = leaseManager.grantLease(chunkHandle, selectedServers)
            return buildResponse(chunkMeta, lease)
        }

        // Grant or reuse lease, bump version for new lease grants
        val existingLease = leaseManager.getActiveLease(chunkHandle)
        val lease = if (existingLease != null) {
            existingLease
        } else {
            chunkManager.incrementVersion(chunkHandle)
            leaseManager.grantLease(chunkHandle, locations)
        }

        return buildResponse(chunkMeta, lease)
    }

    private fun allocateNewChunk(path: String, chunkIndex: Int): gfs.master.chunk.ChunkMetadata {
        val chunk = chunkManager.allocateChunk(path, chunkIndex)
        namespaceTree.addChunkHandle(path, chunk.handle)

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_ALLOCATE_CHUNK)
            .setAllocateChunk(
                AllocateChunkOp.newBuilder()
                    .setPath(path)
                    .setChunkIndex(chunkIndex.toLong())
                    .setChunk(
                        ChunkReference.newBuilder()
                            .setHandle(chunk.handle)
                            .setVersion(chunk.version)
                    )
            )
            .build()
        operationLog.append(entry)

        return chunk
    }

    private fun buildResponse(
        chunkMeta: gfs.master.chunk.ChunkMetadata,
        lease: gfs.master.lease.LeaseInfo
    ): GetWriteTargetResponse {
        val leaseGrant = LeaseGrant.newBuilder()
            .setChunk(
                ChunkReference.newBuilder()
                    .setHandle(chunkMeta.handle)
                    .setVersion(chunkMeta.version)
            )
            .setExpirationTimestamp(lease.expirationTimestamp)
            .setPrimary(lease.primary)
            .addAllSecondaries(lease.secondaries)
            .build()

        return GetWriteTargetResponse.newBuilder()
            .setStatus(okStatus())
            .setLease(leaseGrant)
            .build()
    }
}

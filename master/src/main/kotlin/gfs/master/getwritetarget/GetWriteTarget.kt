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
                if (node.chunkHandles.isEmpty()) 0 else node.chunkHandles.size - 1
            }
            else -> (offset / GfsConfig.CHUNK_SIZE_BYTES).toInt()
        }

        // Allocate chunk if it doesn't exist yet
        val chunkHandle = if (chunkIndex >= node.chunkHandles.size) {
            val chunk = allocateNewChunk(path, chunkIndex)
            chunk.handle
        } else {
            val existingHandle = node.chunkHandles[chunkIndex]
            // Copy-on-write: if chunk is shared (refCount > 1), allocate a new chunk
            val meta = chunkManager.getChunkMetadata(existingHandle)
            if (meta != null && meta.referenceCount > 1) {
                copyOnWrite(path, chunkIndex, existingHandle)
            } else {
                existingHandle
            }
        }

        val chunkMeta = chunkManager.getChunkMetadata(chunkHandle)
            ?: return GetWriteTargetResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Chunk metadata missing for handle $chunkHandle"))
                .build()

        // Find chunkservers that hold this chunk
        val locations = chunkServerRegistry.getLocationsForChunk(chunkHandle)
        if (locations.isEmpty()) {
            val selectedServers = chunkServerRegistry.selectServersForNewChunk(node.replicationFactor)
            if (selectedServers.isEmpty()) {
                return GetWriteTargetResponse.newBuilder()
                    .setStatus(status(StatusCode.INTERNAL_ERROR, "No chunkservers available"))
                    .build()
            }

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

    private fun copyOnWrite(path: String, chunkIndex: Int, oldHandle: Long): Long {
        // Allocate a fresh chunk for this file's exclusive use
        val newChunk = chunkManager.allocateChunk(path, chunkIndex)

        // Replace the handle in the namespace
        namespaceTree.replaceChunkHandle(path, chunkIndex, oldHandle, newChunk.handle)
        chunkManager.replaceChunkForFile(path, chunkIndex, oldHandle, newChunk.handle)

        // Decrement the old chunk's ref count (this file no longer shares it)
        chunkManager.decrementRefCount(oldHandle)

        // WAL the copy-on-write
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_COPY_ON_WRITE)
            .setCopyOnWrite(
                CopyOnWriteOp.newBuilder()
                    .setPath(path)
                    .setChunkIndex(chunkIndex)
                    .setOldHandle(oldHandle)
                    .setNewHandle(newChunk.handle)
            )
            .build()
        operationLog.append(entry)

        return newChunk.handle
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

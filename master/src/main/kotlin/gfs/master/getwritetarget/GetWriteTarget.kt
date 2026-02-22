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
import io.grpc.ManagedChannelBuilder
import java.util.logging.Logger

class GetWriteTarget(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val chunkServerRegistry: ChunkServerRegistry,
    private val leaseManager: LeaseManager,
    private val operationLog: OperationLog
) {

    private val logger = Logger.getLogger(GetWriteTarget::class.java.name)

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
                if (node.chunkHandles.isEmpty()) 0
                else if (request.forceNewChunk) node.chunkHandles.size
                else node.chunkHandles.size - 1
            }
            else -> (offset / GfsConfig.CHUNK_SIZE_BYTES).toInt()
        }

        // Allocate chunk if it doesn't exist yet
        val chunkHandle = if (chunkIndex >= node.chunkHandles.size) {
            val chunk = allocateNewChunk(path, chunkIndex)
            chunk.handle
        } else {
            val existingHandle = node.chunkHandles[chunkIndex]
            val meta = chunkManager.getChunkMetadata(existingHandle)
            if (meta != null && meta.referenceCount > 1) {
                // Copy-on-write: snapshot shares this chunk, need a private copy
                val oldLocations = chunkServerRegistry.getLocationsForChunk(existingHandle)
                val oldVersion = meta.version
                val newHandle = copyOnWrite(path, chunkIndex, existingHandle)
                val newMeta = chunkManager.getChunkMetadata(newHandle)!!

                if (oldLocations.isNotEmpty()) {
                    // Copy data: tell each server to read old chunk locally and write to new chunk
                    copyChunkOnServers(newHandle, newMeta.version, existingHandle, oldVersion, oldLocations)
                    val lease = leaseManager.grantLease(newHandle, oldLocations)
                    return buildResponse(newMeta, lease)
                }
                // Fall through if old locations empty (servers not yet reporting)
                newHandle
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

            // Tell each chunkserver to create the chunk on disk
            createChunkOnServers(chunkHandle, chunkMeta.version, selectedServers)

            val lease = leaseManager.grantLease(chunkHandle, selectedServers)
            return buildResponse(chunkMeta, lease)
        }

        // Grant or reuse lease, bump version for new lease grants
        val existingLease = leaseManager.getActiveLease(chunkHandle)
        val lease = if (existingLease != null) {
            existingLease
        } else {
            val newVersion = chunkManager.incrementVersion(chunkHandle)
            updateVersionOnServers(chunkHandle, newVersion, locations)
            leaseManager.grantLease(chunkHandle, locations)
        }

        // Re-fetch metadata after potential version bump
        val freshMeta = chunkManager.getChunkMetadata(chunkHandle) ?: chunkMeta
        return buildResponse(freshMeta, lease)
    }

    private fun copyOnWrite(path: String, chunkIndex: Int, oldHandle: Long): Long {
        // Allocate a handle without adding to the file's chunk list (replaceChunkForFile does that)
        val newChunk = chunkManager.allocateChunkHandle(path, chunkIndex)

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

    private fun updateVersionOnServers(handle: Long, newVersion: Long, servers: List<ChunkServerAddress>) {
        val request = UpdateChunkVersionRequest.newBuilder()
            .setChunk(ChunkReference.newBuilder().setHandle(handle).setVersion(newVersion))
            .build()

        for (server in servers) {
            try {
                val channel = ManagedChannelBuilder.forTarget(server.endpoint)
                    .usePlaintext()
                    .build()
                try {
                    val stub = ChunkServerServiceGrpc.newBlockingStub(channel)
                    stub.updateChunkVersion(request)
                    logger.info("UpdateChunkVersion($handle→v$newVersion) on ${server.endpoint}")
                } finally {
                    channel.shutdown()
                }
            } catch (e: Exception) {
                logger.warning("Failed to update version for chunk $handle on ${server.endpoint}: ${e.message}")
            }
        }
    }

    private fun copyChunkOnServers(
        newHandle: Long, newVersion: Long,
        oldHandle: Long, oldVersion: Long,
        servers: List<ChunkServerAddress>
    ) {
        val request = CopyChunkRequest.newBuilder()
            .setChunk(ChunkReference.newBuilder().setHandle(newHandle).setVersion(newVersion))
            .setSourceChunk(ChunkReference.newBuilder().setHandle(oldHandle).setVersion(oldVersion))
            .build()

        for (server in servers) {
            try {
                val channel = ManagedChannelBuilder.forTarget(server.endpoint)
                    .usePlaintext()
                    .build()
                try {
                    // Source is the server itself — old chunk data is already local
                    val reqWithSource = request.toBuilder().setSource(server).build()
                    val stub = ChunkServerServiceGrpc.newBlockingStub(channel)
                    val resp = stub.copyChunk(reqWithSource)
                    if (resp.status.code == StatusCode.OK) {
                        chunkServerRegistry.addChunkLocation(server.serverId, newHandle)
                    }
                    logger.info("CopyChunkData($oldHandle→$newHandle) on ${server.endpoint}: ${resp.status.code}")
                } finally {
                    channel.shutdown()
                }
            } catch (e: Exception) {
                logger.warning("Failed to copy chunk $oldHandle→$newHandle on ${server.endpoint}: ${e.message}")
            }
        }
    }

    private fun createChunkOnServers(handle: Long, version: Long, servers: List<ChunkServerAddress>) {
        val request = CreateChunkRequest.newBuilder()
            .setChunk(ChunkReference.newBuilder().setHandle(handle).setVersion(version))
            .build()

        for (server in servers) {
            try {
                val channel = ManagedChannelBuilder.forTarget(server.endpoint)
                    .usePlaintext()
                    .build()
                try {
                    val stub = ChunkServerServiceGrpc.newBlockingStub(channel)
                    val resp = stub.createChunk(request)
                    if (resp.status.code == StatusCode.OK) {
                        chunkServerRegistry.addChunkLocation(server.serverId, handle)
                    }
                    logger.info("CreateChunk($handle) on ${server.endpoint}: ${resp.status.code}")
                } finally {
                    channel.shutdown()
                }
            } catch (e: Exception) {
                logger.warning("Failed to create chunk $handle on ${server.endpoint}: ${e.message}")
            }
        }
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

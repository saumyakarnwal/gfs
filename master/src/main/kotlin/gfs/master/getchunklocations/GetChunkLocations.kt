package gfs.master.getchunklocations

import gfs.common.GfsConfig
import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class GetChunkLocations(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val chunkServerRegistry: ChunkServerRegistry
) {

    fun execute(request: GetChunkLocationsRequest): GetChunkLocationsResponse {
        val path = request.path
        val offset = request.offset
        val length = request.length

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(offset >= 0) { "Offset must be non-negative" }
        checkRequest(length > 0) { "Length must be positive" }

        val node = namespaceTree.getNode(path)
            ?: return GetChunkLocationsResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "File not found: $path"))
                .build()

        checkRequest(!node.isDirectory) { "Cannot get chunk locations for directory: $path" }

        val startChunkIndex = (offset / GfsConfig.CHUNK_SIZE_BYTES).toInt()
        val endChunkIndex = ((offset + length - 1) / GfsConfig.CHUNK_SIZE_BYTES).toInt()

        val allChunks = chunkManager.getChunksForFile(path)
        val chunkInfos = (startChunkIndex..endChunkIndex)
            .filter { it < allChunks.size }
            .map { idx ->
                val meta = allChunks[idx]
                val locations = chunkServerRegistry.getLocationsForChunk(meta.handle)
                ChunkInfo.newBuilder()
                    .setChunk(
                        ChunkReference.newBuilder()
                            .setHandle(meta.handle)
                            .setVersion(meta.version)
                    )
                    .addAllLocations(locations)
                    .build()
            }

        return GetChunkLocationsResponse.newBuilder()
            .setStatus(okStatus())
            .addAllChunks(chunkInfos)
            .build()
    }
}

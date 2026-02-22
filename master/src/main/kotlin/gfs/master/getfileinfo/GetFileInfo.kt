package gfs.master.getfileinfo

import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class GetFileInfo(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val chunkServerRegistry: ChunkServerRegistry
) {

    fun execute(request: GetFileInfoRequest): GetFileInfoResponse {
        val path = request.path

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }

        val node = namespaceTree.getNode(path)
            ?: return GetFileInfoResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Path not found: $path"))
                .build()

        val builder = FileInfo.newBuilder()
            .setPath(node.path)
            .setIsDirectory(node.isDirectory)
            .setCreatedAt(node.createdAt)
            .setModifiedAt(node.modifiedAt)
            .setReplicationFactor(node.replicationFactor)

        if (!node.isDirectory) {
            val chunks = chunkManager.getChunksForFile(path)
            val chunkInfos = chunks.map { meta ->
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
            builder.addAllChunks(chunkInfos)
            builder.fileSize = node.fileSize
        }

        return GetFileInfoResponse.newBuilder()
            .setStatus(okStatus())
            .setFileInfo(builder)
            .build()
    }
}

package gfs.master.listdirectory

import gfs.common.GfsConfig
import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceNode
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class ListDirectory(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val chunkServerRegistry: ChunkServerRegistry
) {

    fun execute(request: ListDirectoryRequest): ListDirectoryResponse {
        val path = request.path

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }

        try {
            val children = namespaceTree.listDirectory(path)
            val entries = children.map { it.toFileInfo() }

            return ListDirectoryResponse.newBuilder()
                .setStatus(okStatus())
                .addAllEntries(entries)
                .build()
        } catch (e: NamespaceException) {
            return ListDirectoryResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }
    }

    private fun NamespaceNode.toFileInfo(): FileInfo {
        val builder = FileInfo.newBuilder()
            .setPath(path)
            .setIsDirectory(isDirectory)
            .setCreatedAt(createdAt)
            .setModifiedAt(modifiedAt)
            .setReplicationFactor(replicationFactor)

        if (!isDirectory) {
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
            // Upper bound — exact size of the last chunk is only known by chunkservers
            builder.fileSize = chunks.size.toLong() * GfsConfig.CHUNK_SIZE_BYTES
        }

        return builder.build()
    }
}

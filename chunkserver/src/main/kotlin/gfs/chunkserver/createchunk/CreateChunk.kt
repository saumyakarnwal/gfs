package gfs.chunkserver.createchunk

import gfs.chunkserver.storage.ChunkStorage
import gfs.proto.*
import gfs.common.grpc.okStatus
import gfs.common.grpc.status

class CreateChunk(private val chunkStorage: ChunkStorage) {

    fun execute(request: CreateChunkRequest): CreateChunkResponse {
        val handle = request.chunk.handle
        val version = request.chunk.version

        if (chunkStorage.hasChunk(handle)) {
            return CreateChunkResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Chunk $handle already exists"))
                .build()
        }

        return try {
            chunkStorage.createChunk(handle, version)
            CreateChunkResponse.newBuilder()
                .setStatus(okStatus("Chunk $handle created"))
                .build()
        } catch (e: Exception) {
            CreateChunkResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Failed to create chunk $handle: ${e.message}"))
                .build()
        }
    }
}

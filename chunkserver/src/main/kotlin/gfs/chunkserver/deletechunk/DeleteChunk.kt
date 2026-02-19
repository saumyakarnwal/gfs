package gfs.chunkserver.deletechunk

import gfs.chunkserver.storage.ChunkStorage
import gfs.proto.*
import gfs.common.grpc.okStatus
import gfs.common.grpc.status

class DeleteChunk(private val chunkStorage: ChunkStorage) {

    fun execute(request: DeleteChunkRequest): DeleteChunkResponse {
        val handle = request.chunk.handle

        if (!chunkStorage.hasChunk(handle)) {
            return DeleteChunkResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Chunk $handle not found"))
                .build()
        }

        return try {
            chunkStorage.deleteChunk(handle)
            DeleteChunkResponse.newBuilder()
                .setStatus(okStatus("Chunk $handle deleted"))
                .build()
        } catch (e: Exception) {
            DeleteChunkResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Failed to delete chunk $handle: ${e.message}"))
                .build()
        }
    }
}

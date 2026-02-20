package gfs.chunkserver.updatechunkversion

import gfs.chunkserver.storage.ChunkStorage
import gfs.common.grpc.okStatus
import gfs.common.grpc.status
import gfs.proto.*

class UpdateChunkVersion(private val chunkStorage: ChunkStorage) {

    fun execute(request: UpdateChunkVersionRequest): UpdateChunkVersionResponse {
        val handle = request.chunk.handle
        val newVersion = request.chunk.version

        if (!chunkStorage.hasChunk(handle)) {
            return UpdateChunkVersionResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Chunk $handle not found"))
                .build()
        }

        chunkStorage.setChunkVersion(handle, newVersion)

        return UpdateChunkVersionResponse.newBuilder()
            .setStatus(okStatus("Chunk $handle version updated to $newVersion"))
            .build()
    }
}

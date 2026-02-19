package gfs.chunkserver.applymutation

import gfs.chunkserver.storage.ChunkStorage
import gfs.common.grpc.okStatus
import gfs.common.grpc.status
import gfs.proto.*

class ApplyMutation(private val chunkStorage: ChunkStorage) {

    fun execute(request: ApplyMutationRequest): ApplyMutationResponse {
        val mutation = request.mutation
        val chunkHandle = mutation.chunk.handle
        val data = mutation.data.toByteArray()

        if (!chunkStorage.hasChunk(chunkHandle)) {
            return ApplyMutationResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Chunk $chunkHandle not found"))
                .build()
        }

        return try {
            when (mutation.type) {
                MutationType.RECORD_APPEND -> {
                    chunkStorage.appendChunk(chunkHandle, data)
                }
                else -> {
                    chunkStorage.writeChunk(chunkHandle, mutation.offset, data)
                }
            }

            ApplyMutationResponse.newBuilder()
                .setStatus(okStatus("Mutation applied"))
                .build()
        } catch (e: Exception) {
            ApplyMutationResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Apply failed: ${e.message}"))
                .build()
        }
    }
}

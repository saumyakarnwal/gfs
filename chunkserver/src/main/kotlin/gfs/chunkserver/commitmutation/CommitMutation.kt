package gfs.chunkserver.commitmutation

import gfs.chunkserver.pushdata.PushData
import gfs.chunkserver.storage.ChunkStorage
import gfs.common.GfsConfig
import gfs.common.grpc.okStatus
import gfs.common.grpc.status
import gfs.proto.*
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

class CommitMutation(
    private val chunkStorage: ChunkStorage,
    private val pushData: PushData,
    private val serverId: String
) {
    private val logger = Logger.getLogger(CommitMutation::class.java.name)
    private val serialCounter = AtomicLong(0)

    fun execute(request: CommitMutationRequest): CommitMutationResponse {
        val chunkHandle = request.chunk.handle
        val dataId = request.dataId
        val mutationType = request.type

        if (!chunkStorage.hasChunk(chunkHandle)) {
            return CommitMutationResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Chunk $chunkHandle not found"))
                .build()
        }

        val data = pushData.getData(dataId)
            ?: return CommitMutationResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Data $dataId not found in cache"))
                .build()

        val serialNumber = serialCounter.incrementAndGet()

        val offset = when (mutationType) {
            MutationType.RECORD_APPEND -> {
                val currentSize = chunkStorage.getChunkSize(chunkHandle)
                if (currentSize + data.size > GfsConfig.CHUNK_SIZE_BYTES) {
                    // Pad the chunk and return CHUNK_FULL
                    return CommitMutationResponse.newBuilder()
                        .setStatus(status(StatusCode.CHUNK_FULL, "Chunk $chunkHandle is full"))
                        .build()
                }
                currentSize
            }
            else -> request.offset
        }

        // Apply locally first (primary)
        try {
            if (mutationType == MutationType.RECORD_APPEND) {
                chunkStorage.appendChunk(chunkHandle, data)
            } else {
                chunkStorage.writeChunk(chunkHandle, offset, data)
            }
        } catch (e: Exception) {
            return CommitMutationResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Write failed: ${e.message}"))
                .build()
        }

        // Build mutation for secondaries
        val mutation = Mutation.newBuilder()
            .setType(mutationType)
            .setChunk(request.chunk)
            .setOffset(offset)
            .setData(com.google.protobuf.ByteString.copyFrom(data))
            .setSerialNumber(serialNumber)
            .build()

        // TODO: Forward to secondaries via ApplyMutation
        // For now, secondaries are reached via the lease info the client has

        pushData.removeData(dataId)

        return CommitMutationResponse.newBuilder()
            .setStatus(okStatus("Mutation committed"))
            .setOffset(offset)
            .build()
    }
}

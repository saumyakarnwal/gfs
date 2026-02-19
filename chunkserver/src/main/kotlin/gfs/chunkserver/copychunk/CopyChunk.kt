package gfs.chunkserver.copychunk

import gfs.chunkserver.storage.ChunkStorage
import gfs.common.grpc.okStatus
import gfs.common.grpc.status
import gfs.proto.*
import io.grpc.ManagedChannelBuilder
import java.util.logging.Level
import java.util.logging.Logger

class CopyChunk(private val chunkStorage: ChunkStorage) {

    private val logger = Logger.getLogger(CopyChunk::class.java.name)

    fun execute(request: CopyChunkRequest): CopyChunkResponse {
        val chunkHandle = request.chunk.handle
        val version = request.chunk.version
        val source = request.source

        if (chunkStorage.hasChunk(chunkHandle)) {
            return CopyChunkResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Chunk $chunkHandle already exists"))
                .build()
        }

        return try {
            // Read from source chunkserver
            val channel = ManagedChannelBuilder
                .forTarget(source.endpoint)
                .usePlaintext()
                .build()
            val stub = ChunkServerServiceGrpc.newBlockingStub(channel)

            val readRequest = ReadChunkRequest.newBuilder()
                .setChunk(request.chunk)
                .setOffset(0)
                .setLength(gfs.common.GfsConfig.CHUNK_SIZE_BYTES)
                .build()

            val readResponse = stub.readChunk(readRequest)
            channel.shutdown()

            if (readResponse.status.code != StatusCode.OK) {
                return CopyChunkResponse.newBuilder()
                    .setStatus(status(StatusCode.INTERNAL_ERROR, "Failed to read from source: ${readResponse.status.message}"))
                    .build()
            }

            // Create chunk locally and write data
            chunkStorage.createChunk(chunkHandle, version)
            val data = readResponse.data.toByteArray()
            if (data.isNotEmpty()) {
                chunkStorage.writeChunk(chunkHandle, 0, data)
            }

            CopyChunkResponse.newBuilder()
                .setStatus(okStatus("Chunk $chunkHandle copied"))
                .build()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "CopyChunk failed for $chunkHandle", e)
            CopyChunkResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Copy failed: ${e.message}"))
                .build()
        }
    }
}

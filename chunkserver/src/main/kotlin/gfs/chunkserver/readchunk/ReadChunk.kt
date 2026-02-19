package gfs.chunkserver.readchunk

import gfs.chunkserver.storage.ChecksumMismatchException
import gfs.chunkserver.storage.ChunkStorage
import gfs.proto.*
import gfs.common.grpc.checkRequest
import gfs.common.grpc.okStatus
import gfs.common.grpc.status

class ReadChunk(private val chunkStorage: ChunkStorage) {

    fun execute(request: ReadChunkRequest): ReadChunkResponse {
        val handle = request.chunk.handle
        val version = request.chunk.version
        val offset = request.offset
        val length = request.length

        checkRequest(length > 0) { "Length must be positive" }
        checkRequest(offset >= 0) { "Offset must be non-negative" }

        if (!chunkStorage.hasChunk(handle)) {
            return ReadChunkResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Chunk $handle not found"))
                .build()
        }

        val storedVersion = chunkStorage.getChunkVersion(handle)
        if (version != 0L && storedVersion != version) {
            return ReadChunkResponse.newBuilder()
                .setStatus(status(StatusCode.STALE_CHUNK, "Version mismatch: requested=$version, stored=$storedVersion"))
                .build()
        }

        return try {
            val data = chunkStorage.readChunk(handle, offset, length)
            ReadChunkResponse.newBuilder()
                .setStatus(okStatus())
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .setBytesRead(data.size.toLong())
                .build()
        } catch (e: ChecksumMismatchException) {
            ReadChunkResponse.newBuilder()
                .setStatus(status(StatusCode.INTERNAL_ERROR, "Checksum verification failed: ${e.message}"))
                .build()
        }
    }
}

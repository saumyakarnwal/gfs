package gfs.chunkserver.pushdata

import gfs.common.GfsConfig
import gfs.common.grpc.checkRequest
import gfs.common.grpc.okStatus
import gfs.common.grpc.status
import gfs.proto.*
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

class PushData {

    private val logger = Logger.getLogger(PushData::class.java.name)

    // In-memory cache of pushed data, keyed by data_id.
    // Data lives here until CommitMutation consumes it or it expires.
    private val dataCache = ConcurrentHashMap<String, ByteArray>()

    fun execute(request: PushDataRequest): PushDataResponse {
        val dataId = request.dataId
        val data = request.data.toByteArray()

        checkRequest(dataId.isNotEmpty()) { "data_id must not be empty" }
        checkRequest(data.size <= GfsConfig.MAX_APPEND_SIZE_BYTES) {
            "Data size ${data.size} exceeds max append size ${GfsConfig.MAX_APPEND_SIZE_BYTES}"
        }

        dataCache[dataId] = data

        // Forward to next chunkserver in the pipeline
        if (request.forwardToCount > 0) {
            val next = request.forwardToList.first()
            val remaining = request.forwardToList.drop(1)
            forwardData(dataId, data, next, remaining)
        }

        return PushDataResponse.newBuilder()
            .setStatus(okStatus("Data cached: $dataId"))
            .build()
    }

    fun getData(dataId: String): ByteArray? = dataCache[dataId]

    fun removeData(dataId: String) {
        dataCache.remove(dataId)
    }

    private fun forwardData(
        dataId: String,
        data: ByteArray,
        next: ChunkServerAddress,
        remaining: List<ChunkServerAddress>
    ) {
        try {
            val channel = ManagedChannelBuilder
                .forTarget(next.endpoint)
                .usePlaintext()
                .build()
            val stub = ChunkServerServiceGrpc.newBlockingStub(channel)

            val forwardRequest = PushDataRequest.newBuilder()
                .setDataId(dataId)
                .setData(com.google.protobuf.ByteString.copyFrom(data))
                .addAllForwardTo(remaining)
                .build()

            stub.pushData(forwardRequest)
            channel.shutdown()
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to forward data to ${next.endpoint}", e)
        }
    }
}

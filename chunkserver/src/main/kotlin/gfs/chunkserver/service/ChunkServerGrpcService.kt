package gfs.chunkserver.service

import gfs.chunkserver.applymutation.ApplyMutation
import gfs.chunkserver.commitmutation.CommitMutation
import gfs.chunkserver.copychunk.CopyChunk
import gfs.chunkserver.createchunk.CreateChunk
import gfs.chunkserver.deletechunk.DeleteChunk
import gfs.chunkserver.pushdata.PushData
import gfs.chunkserver.readchunk.ReadChunk
import gfs.common.grpc.methodImpl
import gfs.proto.*

class ChunkServerGrpcService(
    private val readChunk: ReadChunk,
    private val pushData: PushData,
    private val commitMutation: CommitMutation,
    private val applyMutation: ApplyMutation,
    private val createChunk: CreateChunk,
    private val deleteChunk: DeleteChunk,
    private val copyChunk: CopyChunk
) : ChunkServerServiceGrpcKt.ChunkServerServiceCoroutineImplBase() {

    override suspend fun readChunk(request: ReadChunkRequest) = methodImpl {
        readChunk.execute(request)
    }

    override suspend fun pushData(request: PushDataRequest) = methodImpl {
        pushData.execute(request)
    }

    override suspend fun commitMutation(request: CommitMutationRequest) = methodImpl {
        commitMutation.execute(request)
    }

    override suspend fun applyMutation(request: ApplyMutationRequest) = methodImpl {
        applyMutation.execute(request)
    }

    override suspend fun createChunk(request: CreateChunkRequest) = methodImpl {
        createChunk.execute(request)
    }

    override suspend fun deleteChunk(request: DeleteChunkRequest) = methodImpl {
        deleteChunk.execute(request)
    }

    override suspend fun copyChunk(request: CopyChunkRequest) = methodImpl {
        copyChunk.execute(request)
    }
}

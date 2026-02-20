package gfs.client

import gfs.common.GfsConfig
import gfs.proto.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking

class GfsClient(
    masterHost: String = GfsConfig.MASTER_HOST,
    masterPort: Int = GfsConfig.MASTER_PORT
) {
    private val channel: ManagedChannel = ManagedChannelBuilder
        .forAddress(masterHost, masterPort)
        .usePlaintext()
        .build()

    private val masterStub = MasterServiceGrpcKt.MasterServiceCoroutineStub(channel)

    fun createFile(path: String, replicationFactor: Int = GfsConfig.DEFAULT_REPLICATION_FACTOR): CreateFileResponse {
        return runBlocking {
            masterStub.createFile(
                CreateFileRequest.newBuilder()
                    .setPath(path)
                    .setReplicationFactor(replicationFactor)
                    .build()
            )
        }
    }

    fun deleteFile(path: String): DeleteFileResponse {
        return runBlocking {
            masterStub.deleteFile(
                DeleteFileRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
    }

    fun renameFile(source: String, dest: String): RenameFileResponse {
        return runBlocking {
            masterStub.renameFile(
                RenameFileRequest.newBuilder()
                    .setSourcePath(source)
                    .setDestPath(dest)
                    .build()
            )
        }
    }

    fun mkdir(path: String): CreateDirectoryResponse {
        return runBlocking {
            masterStub.createDirectory(
                CreateDirectoryRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
    }

    fun ls(path: String): ListDirectoryResponse {
        return runBlocking {
            masterStub.listDirectory(
                ListDirectoryRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
    }

    fun info(path: String): GetFileInfoResponse {
        return runBlocking {
            masterStub.getFileInfo(
                GetFileInfoRequest.newBuilder()
                    .setPath(path)
                    .build()
            )
        }
    }

    fun getWriteTarget(
        path: String,
        offset: Long = 0,
        mutationType: MutationType = MutationType.WRITE,
        forceNewChunk: Boolean = false
    ): GetWriteTargetResponse {
        return runBlocking {
            masterStub.getWriteTarget(
                GetWriteTargetRequest.newBuilder()
                    .setPath(path)
                    .setOffset(offset)
                    .setMutationType(mutationType)
                    .setForceNewChunk(forceNewChunk)
                    .build()
            )
        }
    }

    fun getChunkLocations(path: String, offset: Long, length: Long): GetChunkLocationsResponse {
        return runBlocking {
            masterStub.getChunkLocations(
                GetChunkLocationsRequest.newBuilder()
                    .setPath(path)
                    .setOffset(offset)
                    .setLength(length)
                    .build()
            )
        }
    }

    fun readChunk(endpoint: String, chunk: ChunkReference, offset: Long, length: Int): ReadChunkResponse {
        val chunkChannel = ManagedChannelBuilder
            .forTarget(endpoint)
            .usePlaintext()
            .build()
        val chunkStub = ChunkServerServiceGrpcKt.ChunkServerServiceCoroutineStub(chunkChannel)

        return runBlocking {
            chunkStub.readChunk(
                ReadChunkRequest.newBuilder()
                    .setChunk(chunk)
                    .setOffset(offset)
                    .setLength(length)
                    .build()
            )
        }.also {
            chunkChannel.shutdown()
        }
    }

    fun pushData(endpoint: String, dataId: String, data: ByteArray, forwardTo: List<ChunkServerAddress> = emptyList()): PushDataResponse {
        val chunkChannel = ManagedChannelBuilder
            .forTarget(endpoint)
            .usePlaintext()
            .build()
        val chunkStub = ChunkServerServiceGrpcKt.ChunkServerServiceCoroutineStub(chunkChannel)

        return runBlocking {
            chunkStub.pushData(
                PushDataRequest.newBuilder()
                    .setDataId(dataId)
                    .setData(com.google.protobuf.ByteString.copyFrom(data))
                    .addAllForwardTo(forwardTo)
                    .build()
            )
        }.also {
            chunkChannel.shutdown()
        }
    }

    fun commitMutation(
        endpoint: String,
        type: MutationType,
        chunk: ChunkReference,
        dataId: String,
        offset: Long = 0,
        secondaries: List<ChunkServerAddress> = emptyList()
    ): CommitMutationResponse {
        val chunkChannel = ManagedChannelBuilder
            .forTarget(endpoint)
            .usePlaintext()
            .build()
        val chunkStub = ChunkServerServiceGrpcKt.ChunkServerServiceCoroutineStub(chunkChannel)

        return runBlocking {
            chunkStub.commitMutation(
                CommitMutationRequest.newBuilder()
                    .setType(type)
                    .setChunk(chunk)
                    .setDataId(dataId)
                    .setOffset(offset)
                    .addAllSecondaries(secondaries)
                    .build()
            )
        }.also {
            chunkChannel.shutdown()
        }
    }

    fun snapshot(sourcePath: String, destPath: String): CreateSnapshotResponse {
        return runBlocking {
            masterStub.createSnapshot(
                CreateSnapshotRequest.newBuilder()
                    .setSourcePath(sourcePath)
                    .setDestPath(destPath)
                    .build()
            )
        }
    }

    fun close() {
        channel.shutdown()
    }
}

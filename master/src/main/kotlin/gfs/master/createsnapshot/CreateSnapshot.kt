package gfs.master.createsnapshot

import gfs.master.chunk.ChunkManager
import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class CreateSnapshot(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val operationLog: OperationLog
) {

    fun execute(request: CreateSnapshotRequest): CreateSnapshotResponse {
        val sourcePath = request.sourcePath
        val destPath = request.destPath

        checkRequest(PathUtils.validate(sourcePath)) { "Invalid source path: $sourcePath" }
        checkRequest(PathUtils.validate(destPath)) { "Invalid dest path: $destPath" }
        checkRequest(!PathUtils.isRoot(sourcePath)) { "Cannot snapshot root" }
        checkRequest(!PathUtils.isRoot(destPath)) { "Cannot snapshot to root" }

        val sourceNode = namespaceTree.getNode(sourcePath)
            ?: return CreateSnapshotResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Source not found: $sourcePath"))
                .build()

        checkRequest(!sourceNode.isDirectory) { "Cannot snapshot a directory" }

        if (namespaceTree.exists(destPath)) {
            return CreateSnapshotResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Dest already exists: $destPath"))
                .build()
        }

        // WAL first
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_SNAPSHOT)
            .setCreateSnapshot(
                CreateSnapshotOp.newBuilder()
                    .setSourcePath(sourcePath)
                    .setDestPath(destPath)
            )
            .build()
        operationLog.append(entry)

        try {
            // Create the snapshot file with the same replication factor
            namespaceTree.createFile(destPath, sourceNode.replicationFactor)

            // Share all chunk handles — increment ref counts
            for (handle in sourceNode.chunkHandles) {
                val meta = chunkManager.getChunkMetadata(handle) ?: continue
                chunkManager.incrementRefCount(handle)
                chunkManager.addChunkToFile(destPath, handle, meta.chunkIndex)
                namespaceTree.addChunkHandle(destPath, handle)
            }
        } catch (e: NamespaceException) {
            return CreateSnapshotResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        return CreateSnapshotResponse.newBuilder()
            .setStatus(okStatus("Snapshot created: $sourcePath -> $destPath"))
            .build()
    }
}

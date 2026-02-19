package gfs.master.deletefile

import gfs.master.chunk.ChunkManager
import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class DeleteFile(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val operationLog: OperationLog
) {

    fun execute(request: DeleteFileRequest): DeleteFileResponse {
        val path = request.path

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(!PathUtils.isRoot(path)) { "Cannot delete root" }

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_DELETE_FILE)
            .setDeleteFile(
                DeleteFileOp.newBuilder().setPath(path)
            )
            .build()

        operationLog.append(entry)

        try {
            namespaceTree.delete(path)
            chunkManager.removeChunksForFile(path)
        } catch (e: NamespaceException) {
            return DeleteFileResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        return DeleteFileResponse.newBuilder()
            .setStatus(okStatus("Deleted: $path"))
            .build()
    }
}

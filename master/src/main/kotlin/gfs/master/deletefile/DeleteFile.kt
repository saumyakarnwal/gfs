package gfs.master.deletefile

import gfs.master.gc.GarbageCollector
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
    private val operationLog: OperationLog,
    private val garbageCollector: GarbageCollector
) {

    fun execute(request: DeleteFileRequest): DeleteFileResponse {
        val path = request.path

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(!PathUtils.isRoot(path)) { "Cannot delete root" }

        val node = namespaceTree.getNode(path)
            ?: return DeleteFileResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Path not found: $path"))
                .build()

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_DELETE_FILE)
            .setDeleteFile(
                DeleteFileOp.newBuilder().setPath(path)
            )
            .build()

        operationLog.append(entry)

        try {
            if (node.isDirectory) {
                // Directories are deleted immediately
                namespaceTree.delete(path)
            } else {
                // Files are lazily deleted — rename to hidden path, GC collects later.
                // This provides an "undo" window and avoids distributed delete coordination.
                val hiddenPath = garbageCollector.markForDeletion(path)
                namespaceTree.createFile(hiddenPath, node.replicationFactor)
                // Move chunk handles to the hidden file
                for (handle in node.chunkHandles) {
                    namespaceTree.addChunkHandle(hiddenPath, handle)
                }
                namespaceTree.delete(path)
            }
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

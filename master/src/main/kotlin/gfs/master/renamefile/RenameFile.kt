package gfs.master.renamefile

import gfs.master.chunk.ChunkManager
import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class RenameFile(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager,
    private val operationLog: OperationLog
) {

    fun execute(request: RenameFileRequest): RenameFileResponse {
        val source = request.sourcePath
        val dest = request.destPath

        checkRequest(PathUtils.validate(source)) { "Invalid source path: $source" }
        checkRequest(PathUtils.validate(dest)) { "Invalid dest path: $dest" }
        checkRequest(!PathUtils.isRoot(source)) { "Cannot rename root" }
        checkRequest(!PathUtils.isRoot(dest)) { "Cannot rename to root" }

        // Pre-validate before logging to keep the oplog clean
        if (!namespaceTree.exists(source)) {
            return RenameFileResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Source not found: $source"))
                .build()
        }
        if (namespaceTree.exists(dest)) {
            return RenameFileResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Dest already exists: $dest"))
                .build()
        }
        val destParent = PathUtils.parentPath(dest)
        val destParentNode = namespaceTree.getNode(destParent)
            ?: return RenameFileResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Dest parent not found: $destParent"))
                .build()
        if (!destParentNode.isDirectory) {
            return RenameFileResponse.newBuilder()
                .setStatus(status(StatusCode.INVALID_ARGUMENT, "Dest parent is not a directory: $destParent"))
                .build()
        }

        // Log → Apply
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_RENAME_FILE)
            .setRenameFile(
                RenameFileOp.newBuilder()
                    .setSourcePath(source)
                    .setDestPath(dest)
            )
            .build()
        operationLog.append(entry)

        try {
            namespaceTree.rename(source, dest)
            chunkManager.renameFile(source, dest)
        } catch (e: NamespaceException) {
            return RenameFileResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        return RenameFileResponse.newBuilder()
            .setStatus(okStatus("Renamed $source to $dest"))
            .build()
    }
}

package gfs.master.createfile

import gfs.common.GfsConfig
import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class CreateFile(
    private val namespaceTree: NamespaceTree,
    private val operationLog: OperationLog
) {

    fun execute(request: CreateFileRequest): CreateFileResponse {
        val path = request.path
        val replicationFactor = if (request.replicationFactor > 0) {
            request.replicationFactor
        } else {
            GfsConfig.DEFAULT_REPLICATION_FACTOR
        }

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(!PathUtils.isRoot(path)) { "Cannot create file at root" }

        // Pre-validate before logging to keep the oplog clean
        val parentPath = PathUtils.parentPath(path)
        val parent = namespaceTree.getNode(parentPath)
            ?: return CreateFileResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Parent not found: $parentPath"))
                .build()
        if (!parent.isDirectory) {
            return CreateFileResponse.newBuilder()
                .setStatus(status(StatusCode.INVALID_ARGUMENT, "Parent is not a directory: $parentPath"))
                .build()
        }
        if (namespaceTree.exists(path)) {
            return CreateFileResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Already exists: $path"))
                .build()
        }

        // Log → Apply: log the intent before mutating in-memory state
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_FILE)
            .setCreateFile(
                CreateFileOp.newBuilder()
                    .setPath(path)
                    .setReplicationFactor(replicationFactor)
            )
            .build()
        operationLog.append(entry)

        val node = try {
            namespaceTree.createFile(path, replicationFactor)
        } catch (e: NamespaceException) {
            return CreateFileResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        val fileInfo = FileInfo.newBuilder()
            .setPath(node.path)
            .setIsDirectory(false)
            .setCreatedAt(node.createdAt)
            .setModifiedAt(node.modifiedAt)
            .setReplicationFactor(node.replicationFactor)
            .setFileSize(0)
            .build()

        return CreateFileResponse.newBuilder()
            .setStatus(okStatus("File created: $path"))
            .setFileInfo(fileInfo)
            .build()
    }
}

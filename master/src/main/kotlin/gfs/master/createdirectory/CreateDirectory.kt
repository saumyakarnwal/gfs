package gfs.master.createdirectory

import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class CreateDirectory(
    private val namespaceTree: NamespaceTree,
    private val operationLog: OperationLog
) {

    fun execute(request: CreateDirectoryRequest): CreateDirectoryResponse {
        val path = request.path

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(!PathUtils.isRoot(path)) { "Cannot create directory at root" }

        // Pre-validate before logging to keep the oplog clean
        val parentPath = PathUtils.parentPath(path)
        val parent = namespaceTree.getNode(parentPath)
            ?: return CreateDirectoryResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Parent not found: $parentPath"))
                .build()
        if (!parent.isDirectory) {
            return CreateDirectoryResponse.newBuilder()
                .setStatus(status(StatusCode.INVALID_ARGUMENT, "Parent is not a directory: $parentPath"))
                .build()
        }
        if (namespaceTree.exists(path)) {
            return CreateDirectoryResponse.newBuilder()
                .setStatus(status(StatusCode.ALREADY_EXISTS, "Already exists: $path"))
                .build()
        }

        // Log → Apply
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_DIRECTORY)
            .setCreateDirectory(
                CreateDirectoryOp.newBuilder().setPath(path)
            )
            .build()
        operationLog.append(entry)

        try {
            namespaceTree.createDirectory(path)
        } catch (e: NamespaceException) {
            return CreateDirectoryResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        return CreateDirectoryResponse.newBuilder()
            .setStatus(okStatus("Directory created: $path"))
            .build()
    }
}

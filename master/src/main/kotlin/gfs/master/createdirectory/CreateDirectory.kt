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

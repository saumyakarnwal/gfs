package gfs.master.renamefile

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
    private val operationLog: OperationLog
) {

    fun execute(request: RenameFileRequest): RenameFileResponse {
        val source = request.sourcePath
        val dest = request.destPath

        checkRequest(PathUtils.validate(source)) { "Invalid source path: $source" }
        checkRequest(PathUtils.validate(dest)) { "Invalid dest path: $dest" }
        checkRequest(!PathUtils.isRoot(source)) { "Cannot rename root" }
        checkRequest(!PathUtils.isRoot(dest)) { "Cannot rename to root" }

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

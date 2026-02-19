package gfs.master.setreplication

import gfs.master.namespace.NamespaceException
import gfs.master.namespace.NamespaceTree
import gfs.master.namespace.PathUtils
import gfs.master.oplog.OperationLog
import gfs.master.service.checkRequest
import gfs.master.service.okStatus
import gfs.master.service.status
import gfs.proto.*

class SetReplication(
    private val namespaceTree: NamespaceTree,
    private val operationLog: OperationLog
) {

    fun execute(request: SetReplicationRequest): SetReplicationResponse {
        val path = request.path
        val newFactor = request.replicationFactor

        checkRequest(PathUtils.validate(path)) { "Invalid path: $path" }
        checkRequest(!PathUtils.isRoot(path)) { "Cannot set replication on root" }
        checkRequest(newFactor > 0) { "Replication factor must be positive" }

        val node = namespaceTree.getNode(path)
            ?: return SetReplicationResponse.newBuilder()
                .setStatus(status(StatusCode.NOT_FOUND, "Path not found: $path"))
                .build()

        checkRequest(!node.isDirectory) { "Cannot set replication on directory: $path" }

        val previousFactor = node.replicationFactor

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_SET_REPLICATION)
            .setSetReplication(
                SetReplicationOp.newBuilder()
                    .setPath(path)
                    .setReplicationFactor(newFactor)
            )
            .build()

        operationLog.append(entry)

        try {
            namespaceTree.setReplicationFactor(path, newFactor)
        } catch (e: NamespaceException) {
            return SetReplicationResponse.newBuilder()
                .setStatus(status(e.statusCode, e.message))
                .build()
        }

        return SetReplicationResponse.newBuilder()
            .setStatus(okStatus("Replication factor updated: $path"))
            .setPreviousReplicationFactor(previousFactor)
            .build()
    }
}

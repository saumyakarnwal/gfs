package gfs.master.namespace

interface NamespaceTree {

    fun createFile(path: String, replicationFactor: Int): NamespaceNode

    fun createDirectory(path: String): NamespaceNode

    fun delete(path: String)

    fun rename(sourcePath: String, destPath: String)

    fun getNode(path: String): NamespaceNode?

    fun listDirectory(path: String): List<NamespaceNode>

    fun exists(path: String): Boolean

    fun addChunkHandle(path: String, chunkHandle: Long)

    fun replaceChunkHandle(path: String, chunkIndex: Int, oldHandle: Long, newHandle: Long)

    fun setReplicationFactor(path: String, replicationFactor: Int)

    // Snapshot the entire namespace for checkpointing. Returns a deep copy
    // so the checkpoint can be serialized without holding locks.
    fun getAllNodes(): Map<String, NamespaceNode>

    // Replace the entire namespace from a checkpoint during recovery.
    // Called once at startup before any RPCs are served.
    fun restoreFrom(nodes: Map<String, NamespaceNode>)
}

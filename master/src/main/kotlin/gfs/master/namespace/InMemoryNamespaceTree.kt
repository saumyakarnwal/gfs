package gfs.master.namespace

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

// Locking follows GFS paper Section 4.1:
//   - Read-lock all ancestor directories (including parent) to prevent deletion
//   - Write-lock only the target path being mutated
// Since we have no children set on parent nodes, mutations never modify the parent,
// so we never need a write-lock on the parent. This eliminates the read→write lock
// upgrade deadlock that would occur with a children-set design.

class InMemoryNamespaceTree : NamespaceTree {

    private val nodes = ConcurrentHashMap<String, NamespaceNode>()
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    init {
        val root = NamespaceNode(
            path = "/",
            isDirectory = true,
            createdAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis(),
            replicationFactor = 0
        )
        nodes["/"] = root
        locks["/"] = ReentrantReadWriteLock()
    }

    override fun createFile(path: String, replicationFactor: Int): NamespaceNode {
        requireValidPath(path)
        requireNotRoot(path)

        val parentPath = PathUtils.parentPath(path)
        val ancestorReadLocks = acquireAncestorReadLocks(path)
        val targetWriteLock = lockForWrite(path)

        try {
            val parent = nodes[parentPath]
                ?: throw NamespaceException.notFound(parentPath)
            require(parent.isDirectory) { "Parent $parentPath is not a directory" }

            if (nodes.containsKey(path)) {
                throw NamespaceException.alreadyExists(path)
            }

            val now = System.currentTimeMillis()
            val node = NamespaceNode(
                path = path,
                isDirectory = false,
                createdAt = now,
                modifiedAt = now,
                replicationFactor = replicationFactor
            )
            nodes[path] = node
            return node
        } finally {
            targetWriteLock.unlock()
            releaseReadLocks(ancestorReadLocks)
        }
    }

    override fun createDirectory(path: String): NamespaceNode {
        requireValidPath(path)
        requireNotRoot(path)

        val parentPath = PathUtils.parentPath(path)
        val ancestorReadLocks = acquireAncestorReadLocks(path)
        val targetWriteLock = lockForWrite(path)

        try {
            val parent = nodes[parentPath]
                ?: throw NamespaceException.notFound(parentPath)
            require(parent.isDirectory) { "Parent $parentPath is not a directory" }

            if (nodes.containsKey(path)) {
                throw NamespaceException.alreadyExists(path)
            }

            val now = System.currentTimeMillis()
            val node = NamespaceNode(
                path = path,
                isDirectory = true,
                createdAt = now,
                modifiedAt = now,
                replicationFactor = 0
            )
            nodes[path] = node
            return node
        } finally {
            targetWriteLock.unlock()
            releaseReadLocks(ancestorReadLocks)
        }
    }

    override fun delete(path: String) {
        requireValidPath(path)
        requireNotRoot(path)

        val ancestorReadLocks = acquireAncestorReadLocks(path)
        val targetWriteLock = lockForWrite(path)

        try {
            val node = nodes[path]
                ?: throw NamespaceException.notFound(path)

            if (node.isDirectory && hasChildren(path)) {
                throw NamespaceException.directoryNotEmpty(path)
            }

            nodes.remove(path)
            locks.remove(path)
        } finally {
            targetWriteLock.unlock()
            releaseReadLocks(ancestorReadLocks)
        }
    }

    override fun rename(sourcePath: String, destPath: String) {
        requireValidPath(sourcePath)
        requireValidPath(destPath)
        requireNotRoot(sourcePath)
        requireNotRoot(destPath)

        val destParent = PathUtils.parentPath(destPath)

        // Lock in sorted order to prevent deadlocks
        val pathsToWriteLock = sortedSetOf(sourcePath, destPath)
        val allAncestors = (PathUtils.ancestorPaths(sourcePath) + PathUtils.ancestorPaths(destPath))
            .distinct()
            .filter { it !in pathsToWriteLock }
            .sorted()

        val ancestorReadLocks = allAncestors.map { lockForRead(it) }
        val writeLocks = pathsToWriteLock.map { it to lockForWrite(it) }

        try {
            val sourceNode = nodes[sourcePath]
                ?: throw NamespaceException.notFound(sourcePath)

            if (nodes.containsKey(destPath)) {
                throw NamespaceException.alreadyExists(destPath)
            }

            val destParentNode = nodes[destParent]
                ?: throw NamespaceException.notFound(destParent)
            require(destParentNode.isDirectory) { "Destination parent $destParent is not a directory" }

            val renamedNode = sourceNode.copy(
                path = destPath,
                modifiedAt = System.currentTimeMillis()
            )

            nodes.remove(sourcePath)
            locks.remove(sourcePath)
            nodes[destPath] = renamedNode
        } finally {
            writeLocks.reversed().forEach { (_, lock) -> lock.unlock() }
            ancestorReadLocks.reversed().forEach { it.unlock() }
        }
    }

    override fun getNode(path: String): NamespaceNode? {
        val lock = lockForRead(path)
        try {
            return nodes[path]
        } finally {
            lock.unlock()
        }
    }

    // Cold path: computed by scanning all paths with a matching prefix.
    // O(n) where n = total paths. Fine at our scale; could be optimized with a
    // B-tree or trie-based index for production-scale namespaces.
    override fun listDirectory(path: String): List<NamespaceNode> {
        val lock = lockForRead(path)
        try {
            val node = nodes[path]
                ?: throw NamespaceException.notFound(path)
            require(node.isDirectory) { "$path is not a directory" }

            val prefix = if (path == "/") "/" else "$path/"
            return nodes.values.filter { candidate ->
                candidate.path != path &&
                    candidate.path.startsWith(prefix) &&
                    candidate.path.indexOf('/', prefix.length) == -1
            }
        } finally {
            lock.unlock()
        }
    }

    override fun exists(path: String): Boolean = nodes.containsKey(path)

    override fun addChunkHandle(path: String, chunkHandle: Long) {
        val lock = lockForWrite(path)
        try {
            val node = nodes[path]
                ?: throw NamespaceException.notFound(path)
            require(!node.isDirectory) { "Cannot add chunks to directory $path" }
            node.chunkHandles.add(chunkHandle)
            node.modifiedAt = System.currentTimeMillis()
        } finally {
            lock.unlock()
        }
    }

    override fun setReplicationFactor(path: String, replicationFactor: Int) {
        val lock = lockForWrite(path)
        try {
            val node = nodes[path]
                ?: throw NamespaceException.notFound(path)
            node.replicationFactor = replicationFactor
            node.modifiedAt = System.currentTimeMillis()
        } finally {
            lock.unlock()
        }
    }

    override fun getAllNodes(): Map<String, NamespaceNode> = HashMap(nodes)

    override fun restoreFrom(nodes: Map<String, NamespaceNode>) {
        this.nodes.clear()
        this.locks.clear()
        nodes.forEach { (path, node) ->
            this.nodes[path] = node
            this.locks[path] = ReentrantReadWriteLock()
        }
    }

    // Cold path: checks if any path exists under the given directory.
    // Short-circuits on first match so doesn't need to scan everything.
    private fun hasChildren(path: String): Boolean {
        val prefix = if (path == "/") "/" else "$path/"
        return nodes.keys.any { it != path && it.startsWith(prefix) }
    }

    private fun lockForRead(path: String): ReentrantReadWriteLock.ReadLock {
        val rwLock = locks.computeIfAbsent(path) { ReentrantReadWriteLock() }
        val readLock = rwLock.readLock()
        readLock.lock()
        return readLock
    }

    private fun lockForWrite(path: String): ReentrantReadWriteLock.WriteLock {
        val rwLock = locks.computeIfAbsent(path) { ReentrantReadWriteLock() }
        val writeLock = rwLock.writeLock()
        writeLock.lock()
        return writeLock
    }

    // Read-lock all ancestors including parent. Since we never write-lock the parent
    // (no children set to modify), there's no read→write upgrade deadlock.
    private fun acquireAncestorReadLocks(path: String): List<ReentrantReadWriteLock.ReadLock> {
        return PathUtils.ancestorPaths(path).map { lockForRead(it) }
    }

    private fun releaseReadLocks(locks: List<ReentrantReadWriteLock.ReadLock>) {
        locks.reversed().forEach { it.unlock() }
    }

    private fun requireValidPath(path: String) {
        require(PathUtils.validate(path)) { "Invalid path: $path" }
    }

    private fun requireNotRoot(path: String) {
        require(!PathUtils.isRoot(path)) { "Cannot perform this operation on root" }
    }
}

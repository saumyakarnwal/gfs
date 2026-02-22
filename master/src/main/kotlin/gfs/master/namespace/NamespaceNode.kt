package gfs.master.namespace

import kotlinx.serialization.Serializable

// The namespace is a flat lookup table mapping full pathnames to metadata (GFS paper Section 4.1).
// No children set — directory contents are computed by scanning paths with a matching prefix.
// This optimizes the hot path (reads/writes on specific files are O(1) map lookups).
// Listing is a cold path and O(n) scan, which could be optimized with a B-tree or trie-like
// structure if needed, but is perfectly fine at our scale.

// Note to self: we cannot have set of children nodes as then to mutate that we would have to take lock on the parent as well which is not ideal.
// We should be able to create / edit files in the same directory at a given instance. If we decide to have a set of children then in order to mutate that we will have to have a lock on the parent.
@Serializable
data class NamespaceNode(
    val path: String,
    val isDirectory: Boolean,
    val createdAt: Long,
    var modifiedAt: Long,
    var replicationFactor: Int,
    val chunkHandles: MutableList<Long> = mutableListOf(),
    var fileSize: Long = 0
)

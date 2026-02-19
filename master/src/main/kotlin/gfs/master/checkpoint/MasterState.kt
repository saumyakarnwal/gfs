package gfs.master.checkpoint

import gfs.master.chunk.ChunkMetadata
import gfs.master.namespace.NamespaceNode
import kotlinx.serialization.Serializable

@Serializable
data class MasterState(
    val nodes: Map<String, NamespaceNode>,
    val chunks: List<ChunkMetadata>,
    val nextChunkHandle: Long,
    val lastSequenceNumber: Long
)

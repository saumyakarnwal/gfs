package gfs.master.chunk

import kotlinx.serialization.Serializable

@Serializable
data class ChunkMetadata(
    val handle: Long,
    val version: Long,
    val filePath: String,
    val chunkIndex: Int,
    val referenceCount: Int = 1
)

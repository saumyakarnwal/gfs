package gfs.chunkserver.storage

interface ChunkStorage {

    fun createChunk(handle: Long, version: Long)

    fun deleteChunk(handle: Long)

    fun hasChunk(handle: Long): Boolean

    fun readChunk(handle: Long, offset: Long, length: Int): ByteArray

    fun writeChunk(handle: Long, offset: Long, data: ByteArray)

    fun appendChunk(handle: Long, data: ByteArray): Long

    fun getChunkSize(handle: Long): Long

    fun getChunkVersion(handle: Long): Long

    fun setChunkVersion(handle: Long, version: Long)

    fun getAllChunkHandles(): Set<Long>

    fun getChunkInfo(handle: Long): ChunkInfo?
}

data class ChunkInfo(
    val handle: Long,
    val version: Long,
    val sizeBytes: Long
)

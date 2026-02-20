package gfs.master.chunk

interface ChunkManager {

    fun allocateChunk(filePath: String, chunkIndex: Int): ChunkMetadata

    fun getChunksForFile(filePath: String): List<ChunkMetadata>

    fun getChunkMetadata(handle: Long): ChunkMetadata?

    fun incrementVersion(handle: Long): Long

    fun removeChunksForFile(filePath: String): List<ChunkMetadata>

    fun getNextChunkHandle(): Long

    fun getAllChunks(): Collection<ChunkMetadata>

    fun restoreFrom(chunks: Collection<ChunkMetadata>, nextHandle: Long)

    fun incrementRefCount(handle: Long): Int

    fun decrementRefCount(handle: Long): Int

    fun addChunkToFile(filePath: String, handle: Long, chunkIndex: Int)

    fun removeChunk(handle: Long)

    fun replaceChunkForFile(filePath: String, chunkIndex: Int, oldHandle: Long, newHandle: Long)

    fun renameFile(oldPath: String, newPath: String)
}

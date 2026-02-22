package gfs.master.chunk

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryChunkManager : ChunkManager {

    private val nextHandle = AtomicLong(1)
    private val chunksByHandle = ConcurrentHashMap<Long, ChunkMetadata>()
    private val chunksByFile = ConcurrentHashMap<String, MutableList<ChunkMetadata>>()

    override fun allocateChunk(filePath: String, chunkIndex: Int): ChunkMetadata {
        val handle = nextHandle.getAndIncrement()
        val chunk = ChunkMetadata(
            handle = handle,
            version = 1,
            filePath = filePath,
            chunkIndex = chunkIndex
        )
        chunksByHandle[handle] = chunk
        chunksByFile.computeIfAbsent(filePath) { mutableListOf() }.add(chunk)
        return chunk
    }

    override fun getChunksForFile(filePath: String): List<ChunkMetadata> {
        return chunksByFile[filePath]?.toList() ?: emptyList()
    }

    override fun getChunkMetadata(handle: Long): ChunkMetadata? {
        return chunksByHandle[handle]
    }

    override fun incrementVersion(handle: Long): Long {
        val current = chunksByHandle[handle]
            ?: throw IllegalArgumentException("Unknown chunk handle: $handle")
        val updated = current.copy(version = current.version + 1)
        chunksByHandle[handle] = updated
        updateInFileList(current.filePath, handle, updated)
        return updated.version
    }

    override fun removeChunksForFile(filePath: String): List<ChunkMetadata> {
        val removed = chunksByFile.remove(filePath) ?: return emptyList()
        for (chunk in removed) {
            val newRefCount = decrementRefCount(chunk.handle)
            if (newRefCount <= 0) {
                chunksByHandle.remove(chunk.handle)
            }
        }
        return removed
    }

    override fun getNextChunkHandle(): Long = nextHandle.get()

    override fun getAllChunks(): Collection<ChunkMetadata> = chunksByHandle.values.toList()

    override fun restoreFrom(chunks: Collection<ChunkMetadata>, nextHandle: Long) {
        chunksByHandle.clear()
        chunksByFile.clear()
        this.nextHandle.set(nextHandle)
        chunks.forEach { chunk ->
            chunksByHandle[chunk.handle] = chunk
            chunksByFile.computeIfAbsent(chunk.filePath) { mutableListOf() }.add(chunk)
        }
    }

    override fun incrementRefCount(handle: Long): Int {
        val current = chunksByHandle[handle]
            ?: throw IllegalArgumentException("Unknown chunk handle: $handle")
        val updated = current.copy(referenceCount = current.referenceCount + 1)
        chunksByHandle[handle] = updated
        updateInFileList(current.filePath, handle, updated)
        return updated.referenceCount
    }

    override fun decrementRefCount(handle: Long): Int {
        val current = chunksByHandle[handle] ?: return 0
        val newCount = (current.referenceCount - 1).coerceAtLeast(0)
        val updated = current.copy(referenceCount = newCount)
        chunksByHandle[handle] = updated
        updateInFileList(current.filePath, handle, updated)
        return newCount
    }

    override fun addChunkToFile(filePath: String, handle: Long, chunkIndex: Int) {
        val chunk = chunksByHandle[handle]
            ?: throw IllegalArgumentException("Unknown chunk handle: $handle")
        val fileChunk = chunk.copy(filePath = filePath, chunkIndex = chunkIndex)
        chunksByFile.computeIfAbsent(filePath) { mutableListOf() }.add(fileChunk)
    }

    override fun removeChunk(handle: Long) {
        val chunk = chunksByHandle.remove(handle) ?: return
        chunksByFile[chunk.filePath]?.removeIf { it.handle == handle }
    }

    override fun allocateChunkHandle(filePath: String, chunkIndex: Int): ChunkMetadata {
        val handle = nextHandle.getAndIncrement()
        val chunk = ChunkMetadata(
            handle = handle,
            version = 1,
            filePath = filePath,
            chunkIndex = chunkIndex
        )
        chunksByHandle[handle] = chunk
        return chunk
    }

    override fun replaceChunkForFile(filePath: String, chunkIndex: Int, oldHandle: Long, newHandle: Long) {
        val fileChunks = chunksByFile[filePath] ?: return
        val idx = fileChunks.indexOfFirst { it.handle == oldHandle && it.chunkIndex == chunkIndex }
        if (idx >= 0) {
            val newChunk = chunksByHandle[newHandle]
                ?: throw IllegalArgumentException("Unknown chunk handle: $newHandle")
            fileChunks[idx] = newChunk.copy(filePath = filePath, chunkIndex = chunkIndex)
        }
    }

    override fun renameFile(oldPath: String, newPath: String) {
        val chunks = chunksByFile.remove(oldPath) ?: return
        val updated = chunks.map { it.copy(filePath = newPath) }.toMutableList()
        chunksByFile[newPath] = updated
        for (chunk in updated) {
            chunksByHandle[chunk.handle] = chunk
        }
    }

    private fun updateInFileList(filePath: String, handle: Long, updated: ChunkMetadata) {
        chunksByFile[filePath]?.let { list ->
            val idx = list.indexOfFirst { it.handle == handle }
            if (idx >= 0) list[idx] = updated
        }
    }
}

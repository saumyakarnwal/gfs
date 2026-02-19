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
        // Update in file list too
        chunksByFile[current.filePath]?.let { list ->
            val idx = list.indexOfFirst { it.handle == handle }
            if (idx >= 0) list[idx] = updated
        }
        return updated.version
    }

    override fun removeChunksForFile(filePath: String): List<ChunkMetadata> {
        val removed = chunksByFile.remove(filePath) ?: return emptyList()
        removed.forEach { chunksByHandle.remove(it.handle) }
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
}

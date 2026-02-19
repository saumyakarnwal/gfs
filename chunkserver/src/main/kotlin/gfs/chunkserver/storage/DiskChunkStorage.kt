package gfs.chunkserver.storage

import gfs.common.GfsConfig
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.zip.CRC32
import kotlin.concurrent.read
import kotlin.concurrent.write

class DiskChunkStorage(private val dataDir: Path) : ChunkStorage {

    private val chunkLocks = ConcurrentHashMap<Long, ReentrantReadWriteLock>()

    init {
        Files.createDirectories(chunksDir())
        Files.createDirectories(checksumsDir())
    }

    override fun createChunk(handle: Long, version: Long) {
        val lock = lockFor(handle)
        lock.write {
            val dataFile = chunkDataFile(handle)
            if (Files.exists(dataFile)) {
                throw IllegalStateException("Chunk $handle already exists")
            }
            Files.createFile(dataFile)
            writeVersionFile(handle, version)
            writeChecksums(handle, byteArrayOf())
        }
    }

    override fun deleteChunk(handle: Long) {
        val lock = lockFor(handle)
        lock.write {
            Files.deleteIfExists(chunkDataFile(handle))
            Files.deleteIfExists(checksumFile(handle))
            Files.deleteIfExists(versionFile(handle))
            chunkLocks.remove(handle)
        }
    }

    override fun hasChunk(handle: Long): Boolean = Files.exists(chunkDataFile(handle))

    override fun readChunk(handle: Long, offset: Long, length: Int): ByteArray {
        val lock = lockFor(handle)
        lock.read {
            requireChunkExists(handle)
            val dataFile = chunkDataFile(handle)
            val fileSize = Files.size(dataFile)

            if (offset >= fileSize) return byteArrayOf()

            val actualLength = minOf(length.toLong(), fileSize - offset).toInt()
            val data = ByteArray(actualLength)

            Files.newByteChannel(dataFile, StandardOpenOption.READ).use { channel ->
                channel.position(offset)
                val buf = ByteBuffer.wrap(data)
                var totalRead = 0
                while (totalRead < actualLength) {
                    val n = channel.read(buf)
                    if (n == -1) break
                    totalRead += n
                }
            }

            verifyChecksums(handle, data, offset)
            return data
        }
    }

    override fun writeChunk(handle: Long, offset: Long, data: ByteArray) {
        val lock = lockFor(handle)
        lock.write {
            requireChunkExists(handle)
            require(offset + data.size <= GfsConfig.CHUNK_SIZE_BYTES) {
                "Write exceeds chunk size: offset=$offset, len=${data.size}"
            }

            Files.newByteChannel(
                chunkDataFile(handle),
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE
            ).use { channel ->
                channel.position(offset)
                channel.write(ByteBuffer.wrap(data))
            }

            recomputeChecksums(handle)
        }
    }

    override fun appendChunk(handle: Long, data: ByteArray): Long {
        val lock = lockFor(handle)
        lock.write {
            requireChunkExists(handle)
            val currentSize = Files.size(chunkDataFile(handle))
            require(currentSize + data.size <= GfsConfig.CHUNK_SIZE_BYTES) {
                "Append exceeds chunk size: current=$currentSize, append=${data.size}"
            }

            Files.newByteChannel(
                chunkDataFile(handle),
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            ).use { channel ->
                channel.write(ByteBuffer.wrap(data))
            }

            recomputeChecksums(handle)
            return currentSize
        }
    }

    override fun getChunkSize(handle: Long): Long {
        val lock = lockFor(handle)
        lock.read {
            requireChunkExists(handle)
            return Files.size(chunkDataFile(handle))
        }
    }

    override fun getChunkVersion(handle: Long): Long {
        val lock = lockFor(handle)
        lock.read {
            return readVersionFile(handle)
        }
    }

    override fun setChunkVersion(handle: Long, version: Long) {
        val lock = lockFor(handle)
        lock.write {
            writeVersionFile(handle, version)
        }
    }

    override fun getAllChunkHandles(): Set<Long> {
        return Files.list(chunksDir())
            .filter { it.fileName.toString().endsWith(".dat") }
            .map { it.fileName.toString().removeSuffix(".dat").toLong() }
            .toList()
            .toSet()
    }

    override fun getChunkInfo(handle: Long): ChunkInfo? {
        if (!hasChunk(handle)) return null
        val lock = lockFor(handle)
        lock.read {
            return ChunkInfo(
                handle = handle,
                version = readVersionFile(handle),
                sizeBytes = Files.size(chunkDataFile(handle))
            )
        }
    }

    // --- Checksum management ---

    private fun computeBlockChecksums(data: ByteArray): List<Long> {
        val checksums = mutableListOf<Long>()
        var offset = 0
        while (offset < data.size) {
            val blockEnd = minOf(offset + GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES, data.size)
            val crc = CRC32()
            crc.update(data, offset, blockEnd - offset)
            checksums.add(crc.value)
            offset = blockEnd
        }
        return checksums
    }

    private fun verifyChecksums(handle: Long, data: ByteArray, offset: Long) {
        val storedChecksums = readChecksums(handle)
        if (storedChecksums.isEmpty()) return

        val startBlock = (offset / GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES).toInt()
        val endBlock = ((offset + data.size - 1) / GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES).toInt()

        for (blockIdx in startBlock..minOf(endBlock, storedChecksums.size - 1)) {
            val blockStart = blockIdx.toLong() * GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES
            val blockEnd = minOf(blockStart + GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES, getFileSize(handle))

            // Read the full block from disk for checksum verification
            val blockData = readRawBlock(handle, blockStart, (blockEnd - blockStart).toInt())
            val crc = CRC32()
            crc.update(blockData)

            if (crc.value != storedChecksums[blockIdx]) {
                throw ChecksumMismatchException(handle, blockIdx)
            }
        }
    }

    private fun readRawBlock(handle: Long, offset: Long, length: Int): ByteArray {
        val data = ByteArray(length)
        Files.newByteChannel(chunkDataFile(handle), StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            val buf = ByteBuffer.wrap(data)
            var totalRead = 0
            while (totalRead < length) {
                val n = channel.read(buf)
                if (n == -1) break
                totalRead += n
            }
        }
        return data
    }

    private fun recomputeChecksums(handle: Long) {
        val allData = Files.readAllBytes(chunkDataFile(handle))
        writeChecksums(handle, allData)
    }

    private fun writeChecksums(handle: Long, data: ByteArray) {
        val checksums = computeBlockChecksums(data)
        val file = checksumFile(handle)
        Files.newByteChannel(
            file,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            val buf = ByteBuffer.allocate(checksums.size * Long.SIZE_BYTES)
            checksums.forEach { buf.putLong(it) }
            buf.flip()
            channel.write(buf)
        }
    }

    private fun readChecksums(handle: Long): List<Long> {
        val file = checksumFile(handle)
        if (!Files.exists(file) || Files.size(file) == 0L) return emptyList()
        val bytes = Files.readAllBytes(file)
        val buf = ByteBuffer.wrap(bytes)
        val checksums = mutableListOf<Long>()
        while (buf.hasRemaining()) {
            checksums.add(buf.getLong())
        }
        return checksums
    }

    // --- Version file ---

    private fun writeVersionFile(handle: Long, version: Long) {
        Files.writeString(versionFile(handle), version.toString())
    }

    private fun readVersionFile(handle: Long): Long {
        val file = versionFile(handle)
        if (!Files.exists(file)) return 0
        return Files.readString(file).trim().toLong()
    }

    // --- File paths ---

    private fun chunksDir(): Path = dataDir.resolve("chunks")
    private fun checksumsDir(): Path = dataDir.resolve("checksums")
    private fun chunkDataFile(handle: Long): Path = chunksDir().resolve("$handle.dat")
    private fun checksumFile(handle: Long): Path = checksumsDir().resolve("$handle.crc")
    private fun versionFile(handle: Long): Path = chunksDir().resolve("$handle.ver")

    private fun getFileSize(handle: Long): Long = Files.size(chunkDataFile(handle))

    private fun lockFor(handle: Long): ReentrantReadWriteLock =
        chunkLocks.computeIfAbsent(handle) { ReentrantReadWriteLock() }

    private fun requireChunkExists(handle: Long) {
        require(Files.exists(chunkDataFile(handle))) { "Chunk $handle does not exist" }
    }
}

class ChecksumMismatchException(
    val handle: Long,
    val blockIndex: Int
) : RuntimeException("Checksum mismatch on chunk $handle block $blockIndex")

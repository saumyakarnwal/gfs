package gfs.master.oplog

import gfs.proto.OperationLogEntry
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FileOperationLog(private val logDir: Path) : OperationLog {

    private val logFile: Path = logDir.resolve("oplog.bin")
    private val sequenceCounter = AtomicLong(0)
    private val writeLock = ReentrantLock()
    private var outputStream: DataOutputStream? = null

    init {
        Files.createDirectories(logDir)
        if (Files.exists(logFile)) {
            val existing = readAll()
            if (existing.isNotEmpty()) {
                sequenceCounter.set(existing.last().sequenceNumber)
            }
        }
        outputStream = DataOutputStream(
            BufferedOutputStream(FileOutputStream(logFile.toFile(), true))
        )
    }

    override fun append(entry: OperationLogEntry): Long = writeLock.withLock {
        val seq = sequenceCounter.incrementAndGet()
        val withSeq = entry.toBuilder()
            .setSequenceNumber(seq)
            .setTimestamp(System.currentTimeMillis())
            .build()
        val bytes = withSeq.toByteArray()
        outputStream!!.writeInt(bytes.size)
        outputStream!!.write(bytes)
        outputStream!!.flush()
        seq
    }

    override fun readAll(): List<OperationLogEntry> {
        if (!Files.exists(logFile)) return emptyList()
        return readEntries(logFile)
    }

    override fun readFrom(sequenceNumber: Long): List<OperationLogEntry> {
        return readAll().filter { it.sequenceNumber >= sequenceNumber }
    }

    override fun truncateBefore(sequenceNumber: Long) = writeLock.withLock {
        val keep = readAll().filter { it.sequenceNumber >= sequenceNumber }

        // Close current stream, rewrite file, reopen
        outputStream?.close()

        val tempFile = logDir.resolve("oplog.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile.toFile()))).use { out ->
            keep.forEach { entry ->
                val bytes = entry.toByteArray()
                out.writeInt(bytes.size)
                out.write(bytes)
            }
        }
        Files.move(tempFile, logFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        outputStream = DataOutputStream(
            BufferedOutputStream(FileOutputStream(logFile.toFile(), true))
        )
    }

    override fun getLastSequenceNumber(): Long = sequenceCounter.get()

    override fun flush() {
        writeLock.withLock {
            outputStream?.flush()
        }
    }

    override fun close() {
        writeLock.withLock {
            outputStream?.close()
            outputStream = null
        }
    }

    private fun readEntries(file: Path): List<OperationLogEntry> {
        val entries = mutableListOf<OperationLogEntry>()
        DataInputStream(BufferedInputStream(FileInputStream(file.toFile()))).use { input ->
            while (true) {
                val size = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break
                }
                val bytes = ByteArray(size)
                input.readFully(bytes)
                entries.add(OperationLogEntry.parseFrom(bytes))
            }
        }
        return entries
    }
}

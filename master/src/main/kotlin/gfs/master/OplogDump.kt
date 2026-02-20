package gfs.master

import gfs.common.GfsConfig
import gfs.proto.OperationLogEntry
import gfs.proto.OperationType
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

fun main() {
    val logFile = Path.of(GfsConfig.MASTER_DATA_DIR, "oplog", "oplog.bin")

    if (!Files.exists(logFile)) {
        println("No oplog found at $logFile")
        return
    }

    println("=== OPLOG DUMP: $logFile (${Files.size(logFile)} bytes) ===\n")

    val entries = mutableListOf<OperationLogEntry>()
    DataInputStream(BufferedInputStream(FileInputStream(logFile.toFile()))).use { input ->
        while (true) {
            val size = try { input.readInt() } catch (_: EOFException) { break }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            entries.add(OperationLogEntry.parseFrom(bytes))
        }
    }

    if (entries.isEmpty()) {
        println("(empty — no entries)")
        return
    }

    for (entry in entries) {
        val time = Instant.ofEpochMilli(entry.timestamp)
        val seq = entry.sequenceNumber
        val desc = describeEntry(entry)
        println("#$seq  [$time]  $desc")
    }

    println("\n=== ${entries.size} entries total ===")
}

private fun describeEntry(entry: OperationLogEntry): String {
    return when (entry.type) {
        OperationType.OP_CREATE_FILE -> {
            val op = entry.createFile
            "CREATE_FILE  ${op.path}  (replication=${op.replicationFactor})"
        }
        OperationType.OP_DELETE_FILE -> {
            "DELETE_FILE  ${entry.deleteFile.path}"
        }
        OperationType.OP_CREATE_DIRECTORY -> {
            "CREATE_DIR   ${entry.createDirectory.path}"
        }
        OperationType.OP_RENAME_FILE -> {
            val op = entry.renameFile
            "RENAME       ${op.sourcePath} → ${op.destPath}"
        }
        OperationType.OP_ALLOCATE_CHUNK -> {
            val op = entry.allocateChunk
            "ALLOC_CHUNK  ${op.path}  index=${op.chunkIndex}  handle=${op.chunk.handle}"
        }
        OperationType.OP_SET_REPLICATION -> {
            val op = entry.setReplication
            "SET_REPL     ${op.path}  factor=${op.replicationFactor}"
        }
        OperationType.OP_CREATE_SNAPSHOT -> {
            val op = entry.createSnapshot
            "SNAPSHOT     ${op.sourcePath} → ${op.destPath}"
        }
        OperationType.OP_COPY_ON_WRITE -> {
            val op = entry.copyOnWrite
            "COPY_ON_WRITE  ${op.path}  index=${op.chunkIndex}  old=${op.oldHandle} → new=${op.newHandle}"
        }
        else -> "UNKNOWN(${entry.type})"
    }
}

package gfs.client

import gfs.common.GfsConfig
import gfs.proto.MutationType
import gfs.proto.StatusCode
import java.util.UUID

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsage()
        return
    }

    val client = GfsClient()

    try {
        when (args[0]) {
            "mkdir" -> {
                require(args.size >= 2) { "Usage: mkdir <path>" }
                val resp = client.mkdir(args[1])
                println(formatStatus(resp.status.code, resp.status.message))
            }

            "create" -> {
                require(args.size >= 2) { "Usage: create <path> [replication_factor]" }
                val replication = args.getOrNull(2)?.toIntOrNull() ?: GfsConfig.DEFAULT_REPLICATION_FACTOR
                val resp = client.createFile(args[1], replication)
                println(formatStatus(resp.status.code, resp.status.message))
                if (resp.hasFileInfo()) {
                    printFileInfo(resp.fileInfo)
                }
            }

            "delete", "rm" -> {
                require(args.size >= 2) { "Usage: delete <path>" }
                val resp = client.deleteFile(args[1])
                println(formatStatus(resp.status.code, resp.status.message))
            }

            "rename", "mv" -> {
                require(args.size >= 3) { "Usage: rename <source> <dest>" }
                val resp = client.renameFile(args[1], args[2])
                println(formatStatus(resp.status.code, resp.status.message))
            }

            "ls" -> {
                val path = args.getOrNull(1) ?: "/"
                val resp = client.ls(path)
                if (resp.status.code != StatusCode.OK) {
                    println(formatStatus(resp.status.code, resp.status.message))
                    return
                }
                if (resp.entriesList.isEmpty()) {
                    println("(empty)")
                } else {
                    for (entry in resp.entriesList) {
                        val type = if (entry.isDirectory) "DIR " else "FILE"
                        val size = if (!entry.isDirectory) " ${formatSize(entry.fileSize)}" else ""
                        val chunks = if (!entry.isDirectory) " [${entry.chunksCount} chunks, r=${entry.replicationFactor}]" else ""
                        println("$type ${entry.path}$size$chunks")
                    }
                }
            }

            "info" -> {
                require(args.size >= 2) { "Usage: info <path>" }
                val resp = client.info(args[1])
                if (resp.status.code != StatusCode.OK) {
                    println(formatStatus(resp.status.code, resp.status.message))
                    return
                }
                printFileInfo(resp.fileInfo)
            }

            "put" -> {
                require(args.size >= 3) { "Usage: put <path> [--offset N] <data>" }
                val path = args[1]
                val remaining = args.drop(2)
                val (offset, data) = if (remaining.size >= 2 && remaining[0] == "--offset") {
                    remaining[1].toLong() to remaining.drop(2).joinToString(" ").toByteArray()
                } else {
                    0L to remaining.joinToString(" ").toByteArray()
                }
                writeData(client, path, data, MutationType.WRITE, offset)
            }

            "append" -> {
                require(args.size >= 3) { "Usage: append <path> <data>" }
                val path = args[1]
                val data = args.drop(2).joinToString(" ").toByteArray()
                writeData(client, path, data, MutationType.RECORD_APPEND, 0)
            }

            "get" -> {
                require(args.size >= 2) { "Usage: get <path> [offset] [length]" }
                val path = args[1]
                val offset = args.getOrNull(2)?.toLongOrNull() ?: 0
                val length = args.getOrNull(3)?.toLongOrNull() ?: Long.MAX_VALUE
                readData(client, path, offset, length)
            }

            "fill" -> {
                require(args.size >= 3) { "Usage: fill <path> <sizeKB>" }
                val path = args[1]
                val sizeKB = args[2].toInt()
                val totalBytes = sizeKB * 1024
                println("Filling $path with ${sizeKB}KB (${totalBytes} bytes)...")

                val chunkSize = GfsConfig.MAX_APPEND_SIZE_BYTES
                var written = 0
                var seq = 0
                while (written < totalBytes) {
                    val batchSize = minOf(chunkSize, totalBytes - written)
                    val padding = maxOf(0, batchSize - 20)
                    val line = "record-${seq++}-${"x".repeat(padding)}\n"
                    val data = line.toByteArray().copyOf(batchSize)
                    val ok = writeData(client, path, data, MutationType.RECORD_APPEND, 0)
                    if (!ok) {
                        println("Fill failed at $written bytes")
                        break
                    }
                    written += data.size
                    println("  written so far: $written / $totalBytes")
                }
                println("Done: wrote $written bytes to $path")
            }

            "snapshot", "snap" -> {
                require(args.size >= 3) { "Usage: snapshot <source> <dest>" }
                val resp = client.snapshot(args[1], args[2])
                println(formatStatus(resp.status.code, resp.status.message))
            }

            else -> {
                println("Unknown command: ${args[0]}")
                printUsage()
            }
        }
    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        client.close()
    }
}

private fun writeData(client: GfsClient, path: String, data: ByteArray, type: MutationType, offset: Long): Boolean {
    var forceNewChunk = false
    var retries = 0

    while (retries < 3) {
        val writeResp = client.getWriteTarget(path, offset, type, forceNewChunk)
        if (writeResp.status.code != StatusCode.OK) {
            println("GetWriteTarget failed: ${writeResp.status.message}")
            return false
        }

        val lease = writeResp.lease
        val primary = lease.primary
        val secondaries = lease.secondariesList

        val dataId = UUID.randomUUID().toString()
        val pushResp = client.pushData(primary.endpoint, dataId, data, secondaries)
        if (pushResp.status.code != StatusCode.OK) {
            println("PushData failed: ${pushResp.status.message}")
            return false
        }

        val commitResp = client.commitMutation(primary.endpoint, type, lease.chunk, dataId, offset, secondaries)
        if (commitResp.status.code == StatusCode.CHUNK_FULL) {
            forceNewChunk = true
            retries++
            continue
        }
        if (commitResp.status.code != StatusCode.OK) {
            println("CommitMutation failed: ${commitResp.status.message}")
            return false
        }

        val newFileSize = if (type == MutationType.RECORD_APPEND) {
            println("Appended ${data.size} bytes at offset ${commitResp.offset}")
            commitResp.offset + data.size
        } else {
            println("Wrote ${data.size} bytes at offset $offset")
            offset + data.size
        }
        client.reportFileSize(path, newFileSize)
        return true
    }
    println("Failed after $retries retries (chunk full)")
    return false
}

private fun readData(client: GfsClient, path: String, offset: Long, length: Long) {
    val locResp = client.getChunkLocations(path, offset, length)
    if (locResp.status.code != StatusCode.OK) {
        println("GetChunkLocations failed: ${locResp.status.message}")
        return
    }

    if (locResp.chunksList.isEmpty()) {
        println("(no data)")
        return
    }

    val result = StringBuilder()
    var bytesRemaining = length

    for ((i, chunkInfo) in locResp.chunksList.withIndex()) {
        if (chunkInfo.locationsList.isEmpty()) {
            println("Warning: no locations for chunk ${chunkInfo.chunk.handle}")
            continue
        }

        // Compute the byte range this chunk covers in the file
        val chunkStartInFile = ((offset / GfsConfig.CHUNK_SIZE_BYTES) + i) * GfsConfig.CHUNK_SIZE_BYTES
        val readStart = (offset - chunkStartInFile).coerceAtLeast(0)
        val readLength = bytesRemaining.coerceAtMost(GfsConfig.CHUNK_SIZE_BYTES.toLong() - readStart)

        val location = chunkInfo.locationsList.first()
        val readResp = client.readChunk(
            location.endpoint, chunkInfo.chunk, readStart, readLength.toInt()
        )
        if (readResp.status.code != StatusCode.OK) {
            println("ReadChunk failed on ${location.endpoint}: ${readResp.status.message}")
            continue
        }

        result.append(readResp.data.toStringUtf8())
        bytesRemaining -= readResp.data.size()
        if (bytesRemaining <= 0) break
    }

    println(result.toString())
}

private fun printFileInfo(info: gfs.proto.FileInfo) {
    val type = if (info.isDirectory) "directory" else "file"
    println("  Path: ${info.path}")
    println("  Type: $type")
    if (!info.isDirectory) {
        println("  Size: ${formatSize(info.fileSize)}")
        println("  Chunks: ${info.chunksCount}")
        println("  Replication: ${info.replicationFactor}")
    }
    println("  Created: ${java.time.Instant.ofEpochMilli(info.createdAt)}")
    println("  Modified: ${java.time.Instant.ofEpochMilli(info.modifiedAt)}")
}

private fun formatStatus(code: StatusCode, message: String): String {
    return if (code == StatusCode.OK) {
        "OK${if (message.isNotEmpty()) ": $message" else ""}"
    } else {
        "ERROR [$code]: $message"
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

private fun printUsage() {
    println("""
        GFS Client CLI

        Commands:
          mkdir  <path>                    Create a directory
          create <path> [replication]      Create a file
          delete <path>                    Delete a file or directory
          rename <source> <dest>           Rename a file or directory
          ls     [path]                    List directory (default: /)
          info   <path>                    Get file/directory info
          put    <path> [--offset N] <data> Write data to a file (default offset: 0)
          append <path> <data>             Append data to a file
          get    <path> [offset] [length]  Read data from a file
          fill   <path> <sizeKB>           Fill a file with generated data
          snapshot <source> <dest>         Create a snapshot (copy-on-write)
    """.trimIndent())
}

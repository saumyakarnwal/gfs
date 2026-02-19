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
                require(args.size >= 3) { "Usage: put <path> <data>" }
                val path = args[1]
                val data = args.drop(2).joinToString(" ").toByteArray()
                writeData(client, path, data, MutationType.WRITE, 0)
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
                val length = args.getOrNull(3)?.toLongOrNull() ?: GfsConfig.CHUNK_SIZE_BYTES.toLong()
                readData(client, path, offset, length)
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

private fun writeData(client: GfsClient, path: String, data: ByteArray, type: MutationType, offset: Long) {
    // Step 1: Get write target from master
    val writeResp = client.getWriteTarget(path, offset, type)
    if (writeResp.status.code != StatusCode.OK) {
        println("GetWriteTarget failed: ${writeResp.status.message}")
        return
    }

    val lease = writeResp.lease
    val primary = lease.primary
    val secondaries = lease.secondariesList
    // Step 2: Push data to primary, forward to secondaries
    val dataId = UUID.randomUUID().toString()
    val pushResp = client.pushData(primary.endpoint, dataId, data, secondaries)
    if (pushResp.status.code != StatusCode.OK) {
        println("PushData failed: ${pushResp.status.message}")
        return
    }

    // Step 3: Tell primary to commit (pass secondaries so primary can forward)
    val commitResp = client.commitMutation(primary.endpoint, type, lease.chunk, dataId, offset, secondaries)
    if (commitResp.status.code != StatusCode.OK) {
        println("CommitMutation failed: ${commitResp.status.message}")
        return
    }

    if (type == MutationType.RECORD_APPEND) {
        println("Appended ${data.size} bytes at offset ${commitResp.offset}")
    } else {
        println("Wrote ${data.size} bytes at offset $offset")
    }
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
    for (chunkInfo in locResp.chunksList) {
        if (chunkInfo.locationsList.isEmpty()) {
            println("Warning: no locations for chunk ${chunkInfo.chunk.handle}")
            continue
        }

        val location = chunkInfo.locationsList.first()
        val chunkOffset = 0L
        val chunkLength = GfsConfig.CHUNK_SIZE_BYTES

        val readResp = client.readChunk(
            location.endpoint, chunkInfo.chunk, chunkOffset, chunkLength
        )
        if (readResp.status.code != StatusCode.OK) {
            println("ReadChunk failed on ${location.endpoint}: ${readResp.status.message}")
            continue
        }

        result.append(readResp.data.toStringUtf8())
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
          put    <path> <data>             Write data to a file
          append <path> <data>             Append data to a file
          get    <path> [offset] [length]  Read data from a file
          snapshot <source> <dest>         Create a snapshot (copy-on-write)
    """.trimIndent())
}

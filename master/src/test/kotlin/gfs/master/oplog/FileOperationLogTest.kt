package gfs.master.oplog

import gfs.proto.CreateFileOp
import gfs.proto.OperationLogEntry
import gfs.proto.OperationType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class FileOperationLogTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `append and read entries`() {
        val log = FileOperationLog(tempDir)

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_FILE)
            .setCreateFile(CreateFileOp.newBuilder().setPath("/test").setReplicationFactor(2))
            .build()

        val seq = log.append(entry)
        assertEquals(1L, seq)

        val entries = log.readAll()
        assertEquals(1, entries.size)
        assertEquals(OperationType.OP_CREATE_FILE, entries[0].type)
        assertEquals("/test", entries[0].createFile.path)
        assertEquals(1L, entries[0].sequenceNumber)

        log.close()
    }

    @Test
    fun `sequence numbers are monotonic`() {
        val log = FileOperationLog(tempDir)

        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_FILE)
            .setCreateFile(CreateFileOp.newBuilder().setPath("/a").setReplicationFactor(1))
            .build()

        assertEquals(1L, log.append(entry))
        assertEquals(2L, log.append(entry))
        assertEquals(3L, log.append(entry))

        log.close()
    }

    @Test
    fun `survives restart`() {
        val entry = OperationLogEntry.newBuilder()
            .setType(OperationType.OP_CREATE_FILE)
            .setCreateFile(CreateFileOp.newBuilder().setPath("/file").setReplicationFactor(2))
            .build()

        val log1 = FileOperationLog(tempDir)
        log1.append(entry)
        log1.append(entry)
        log1.close()

        // Reopen — should recover sequence counter
        val log2 = FileOperationLog(tempDir)
        assertEquals(3L, log2.append(entry))
        assertEquals(3, log2.readAll().size)
        log2.close()
    }

    @Test
    fun `readFrom filters by sequence number`() {
        val log = FileOperationLog(tempDir)

        for (i in 1..5) {
            val entry = OperationLogEntry.newBuilder()
                .setType(OperationType.OP_CREATE_FILE)
                .setCreateFile(CreateFileOp.newBuilder().setPath("/file$i").setReplicationFactor(1))
                .build()
            log.append(entry)
        }

        val entries = log.readFrom(3L)
        assertEquals(3, entries.size)
        assertEquals(3L, entries[0].sequenceNumber)

        log.close()
    }

    @Test
    fun `truncateBefore removes old entries`() {
        val log = FileOperationLog(tempDir)

        for (i in 1..5) {
            val entry = OperationLogEntry.newBuilder()
                .setType(OperationType.OP_CREATE_FILE)
                .setCreateFile(CreateFileOp.newBuilder().setPath("/file$i").setReplicationFactor(1))
                .build()
            log.append(entry)
        }

        log.truncateBefore(4L)

        val remaining = log.readAll()
        assertEquals(2, remaining.size)
        assertEquals(4L, remaining[0].sequenceNumber)
        assertEquals(5L, remaining[1].sequenceNumber)

        log.close()
    }
}

package gfs.chunkserver.storage

import gfs.common.GfsConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskChunkStorageTest {

    private lateinit var tempDir: Path
    private lateinit var storage: DiskChunkStorage

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("gfs-chunk-test")
        storage = DiskChunkStorage(tempDir)
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `createChunk creates an empty chunk`() {
        storage.createChunk(1L, 1L)

        assertTrue(storage.hasChunk(1L))
        assertEquals(0L, storage.getChunkSize(1L))
        assertEquals(1L, storage.getChunkVersion(1L))
    }

    @Test
    fun `createChunk fails if chunk already exists`() {
        storage.createChunk(1L, 1L)
        assertThrows<IllegalStateException> {
            storage.createChunk(1L, 1L)
        }
    }

    @Test
    fun `deleteChunk removes chunk`() {
        storage.createChunk(1L, 1L)
        storage.deleteChunk(1L)

        assertFalse(storage.hasChunk(1L))
    }

    @Test
    fun `writeChunk and readChunk roundtrip`() {
        storage.createChunk(1L, 1L)
        val data = "hello world".toByteArray()

        storage.writeChunk(1L, 0, data)

        val read = storage.readChunk(1L, 0, data.size)
        assertContentEquals(data, read)
    }

    @Test
    fun `writeChunk at offset`() {
        storage.createChunk(1L, 1L)
        val padding = ByteArray(100)
        storage.writeChunk(1L, 0, padding)

        val data = "offset-data".toByteArray()
        storage.writeChunk(1L, 100, data)

        val read = storage.readChunk(1L, 100, data.size)
        assertContentEquals(data, read)
    }

    @Test
    fun `appendChunk returns the offset where data was written`() {
        storage.createChunk(1L, 1L)
        val data1 = "first".toByteArray()
        val data2 = "second".toByteArray()

        val offset1 = storage.appendChunk(1L, data1)
        val offset2 = storage.appendChunk(1L, data2)

        assertEquals(0L, offset1)
        assertEquals(data1.size.toLong(), offset2)
    }

    @Test
    fun `readChunk at offset beyond file returns empty`() {
        storage.createChunk(1L, 1L)
        storage.writeChunk(1L, 0, "small".toByteArray())

        val read = storage.readChunk(1L, 1000, 100)
        assertEquals(0, read.size)
    }

    @Test
    fun `readChunk returns partial data when length exceeds available`() {
        storage.createChunk(1L, 1L)
        val data = "hello".toByteArray()
        storage.writeChunk(1L, 0, data)

        val read = storage.readChunk(1L, 0, 1000)
        assertContentEquals(data, read)
    }

    @Test
    fun `write that exceeds chunk size is rejected`() {
        storage.createChunk(1L, 1L)
        val oversized = ByteArray(GfsConfig.CHUNK_SIZE_BYTES + 1)
        assertThrows<IllegalArgumentException> {
            storage.writeChunk(1L, 0, oversized)
        }
    }

    @Test
    fun `setChunkVersion and getChunkVersion roundtrip`() {
        storage.createChunk(1L, 1L)
        assertEquals(1L, storage.getChunkVersion(1L))

        storage.setChunkVersion(1L, 5L)
        assertEquals(5L, storage.getChunkVersion(1L))
    }

    @Test
    fun `getAllChunkHandles returns all created chunks`() {
        storage.createChunk(1L, 1L)
        storage.createChunk(2L, 1L)
        storage.createChunk(3L, 1L)

        val handles = storage.getAllChunkHandles()
        assertEquals(setOf(1L, 2L, 3L), handles)
    }

    @Test
    fun `getChunkInfo returns correct metadata`() {
        storage.createChunk(42L, 3L)
        storage.writeChunk(42L, 0, "test-data".toByteArray())

        val info = storage.getChunkInfo(42L)
        assertNotNull(info)
        assertEquals(42L, info.handle)
        assertEquals(3L, info.version)
        assertEquals(9L, info.sizeBytes)
    }

    @Test
    fun `getChunkInfo returns null for missing chunk`() {
        assertNull(storage.getChunkInfo(999L))
    }

    @Test
    fun `checksum verification detects corruption`() {
        storage.createChunk(1L, 1L)
        val data = ByteArray(GfsConfig.CHECKSUM_BLOCK_SIZE_BYTES) { it.toByte() }
        storage.writeChunk(1L, 0, data)

        // Corrupt the data file directly
        val dataFile = tempDir.resolve("chunks/1.dat")
        val bytes = Files.readAllBytes(dataFile)
        bytes[0] = (bytes[0] + 1).toByte()
        Files.write(dataFile, bytes)

        assertThrows<ChecksumMismatchException> {
            storage.readChunk(1L, 0, data.size)
        }
    }
}

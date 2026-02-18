package gfs.common

object GfsConfig {

    // Network
    const val MASTER_HOST = "localhost"
    const val MASTER_PORT = 50051
    const val CHUNKSERVER_BASE_PORT = 50052
    const val DEFAULT_NUM_CHUNKSERVERS = 3

    // Chunk sizing
    const val CHUNK_SIZE_BYTES: Int = 1 * 1024 * 1024  // 1MB (paper uses 64MB)
    const val MAX_APPEND_SIZE_BYTES: Int = CHUNK_SIZE_BYTES / 4

    // Replication
    const val DEFAULT_REPLICATION_FACTOR = 2

    // Leases
    const val LEASE_TIMEOUT_MS: Long = 60_000L
    const val LEASE_EXTENSION_INTERVAL_MS: Long = 45_000L

    // Heartbeat
    const val HEARTBEAT_INTERVAL_MS: Long = 5_000L
    const val HEARTBEAT_MISS_THRESHOLD = 3  // 3 missed = chunkserver considered dead

    // Checksums — 64KB blocks, matching GFS design
    const val CHECKSUM_BLOCK_SIZE_BYTES: Int = 64 * 1024

    // Operation log
    const val OPLOG_CHECKPOINT_THRESHOLD = 1000

    // Garbage collection
    const val GC_HIDDEN_FILE_TTL_MS: Long = 60_000L
    const val GC_INTERVAL_MS: Long = 30_000L

    // Storage paths
    const val DATA_ROOT = "/tmp/gfs"
    const val MASTER_DATA_DIR = "$DATA_ROOT/master"
    const val CHUNKSERVER_DATA_DIR = "$DATA_ROOT/chunkserver"

    // Helpers
    fun chunkserverPort(index: Int): Int = CHUNKSERVER_BASE_PORT + index
    fun chunkserverAddress(index: Int): String = "$MASTER_HOST:${chunkserverPort(index)}"
    fun chunkserverDataDir(port: Int): String = "$CHUNKSERVER_DATA_DIR/$port"
}

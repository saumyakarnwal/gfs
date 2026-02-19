package gfs.master.lease

import gfs.proto.ChunkServerAddress

interface LeaseManager {

    fun grantLease(
        chunkHandle: Long,
        candidates: List<ChunkServerAddress>
    ): LeaseInfo

    fun extendLease(chunkHandle: Long, serverId: String): LeaseInfo?

    fun revokeLease(chunkHandle: Long)

    fun getActiveLease(chunkHandle: Long): LeaseInfo?

    fun hasValidLease(chunkHandle: Long): Boolean

    fun cleanupExpired()
}

data class LeaseInfo(
    val chunkHandle: Long,
    val primary: ChunkServerAddress,
    val secondaries: List<ChunkServerAddress>,
    val expirationTimestamp: Long
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expirationTimestamp
}

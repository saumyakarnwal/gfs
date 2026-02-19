package gfs.master.lease

import gfs.common.GfsConfig
import gfs.proto.ChunkServerAddress
import java.util.concurrent.ConcurrentHashMap

class InMemoryLeaseManager : LeaseManager {

    private val leases = ConcurrentHashMap<Long, LeaseInfo>()

    override fun grantLease(
        chunkHandle: Long,
        candidates: List<ChunkServerAddress>
    ): LeaseInfo {
        require(candidates.isNotEmpty()) { "Need at least one candidate for lease" }

        val existing = leases[chunkHandle]
        if (existing != null && !existing.isExpired()) {
            return existing
        }

        val primary = candidates.first()
        val secondaries = candidates.drop(1)
        val lease = LeaseInfo(
            chunkHandle = chunkHandle,
            primary = primary,
            secondaries = secondaries,
            expirationTimestamp = System.currentTimeMillis() + GfsConfig.LEASE_TIMEOUT_MS
        )
        leases[chunkHandle] = lease
        return lease
    }

    override fun extendLease(chunkHandle: Long, serverId: String): LeaseInfo? {
        val lease = leases[chunkHandle] ?: return null
        if (lease.primary.serverId != serverId) return null
        if (lease.isExpired()) return null

        val extended = lease.copy(
            expirationTimestamp = System.currentTimeMillis() + GfsConfig.LEASE_TIMEOUT_MS
        )
        leases[chunkHandle] = extended
        return extended
    }

    override fun revokeLease(chunkHandle: Long) {
        leases.remove(chunkHandle)
    }

    override fun getActiveLease(chunkHandle: Long): LeaseInfo? {
        val lease = leases[chunkHandle] ?: return null
        if (lease.isExpired()) {
            leases.remove(chunkHandle)
            return null
        }
        return lease
    }

    override fun hasValidLease(chunkHandle: Long): Boolean {
        return getActiveLease(chunkHandle) != null
    }

    override fun cleanupExpired() {
        val now = System.currentTimeMillis()
        leases.entries.removeIf { it.value.expirationTimestamp < now }
    }
}

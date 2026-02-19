package gfs.master.lease

import gfs.proto.ChunkServerAddress
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class InMemoryLeaseManagerTest {

    private lateinit var leaseManager: InMemoryLeaseManager

    private fun addr(id: String) = ChunkServerAddress.newBuilder()
        .setServerId(id)
        .setEndpoint("localhost:${id.hashCode()}")
        .build()

    @BeforeEach
    fun setUp() {
        leaseManager = InMemoryLeaseManager()
    }

    @Test
    fun `grantLease returns lease with primary and secondaries`() {
        val candidates = listOf(addr("cs-1"), addr("cs-2"), addr("cs-3"))
        val lease = leaseManager.grantLease(1L, candidates)

        assertEquals(1L, lease.chunkHandle)
        assertEquals("cs-1", lease.primary.serverId)
        assertEquals(2, lease.secondaries.size)
        assertEquals("cs-2", lease.secondaries[0].serverId)
        assertEquals("cs-3", lease.secondaries[1].serverId)
        assertFalse(lease.isExpired())
    }

    @Test
    fun `grantLease returns existing lease if still valid`() {
        val candidates = listOf(addr("cs-1"), addr("cs-2"))
        val first = leaseManager.grantLease(1L, candidates)
        val second = leaseManager.grantLease(1L, listOf(addr("cs-3")))

        // Should return the existing lease, not grant a new one
        assertEquals(first.primary.serverId, second.primary.serverId)
    }

    @Test
    fun `getActiveLease returns lease when valid`() {
        leaseManager.grantLease(1L, listOf(addr("cs-1")))

        val lease = leaseManager.getActiveLease(1L)
        assertNotNull(lease)
        assertEquals(1L, lease.chunkHandle)
    }

    @Test
    fun `getActiveLease returns null for unknown chunk`() {
        assertNull(leaseManager.getActiveLease(999L))
    }

    @Test
    fun `extendLease extends for the correct primary`() {
        leaseManager.grantLease(1L, listOf(addr("cs-1"), addr("cs-2")))

        val extended = leaseManager.extendLease(1L, "cs-1")
        assertNotNull(extended)
        assertTrue(extended.expirationTimestamp > System.currentTimeMillis())
    }

    @Test
    fun `extendLease fails for wrong server`() {
        leaseManager.grantLease(1L, listOf(addr("cs-1")))

        val result = leaseManager.extendLease(1L, "cs-2")
        assertNull(result)
    }

    @Test
    fun `revokeLease removes lease`() {
        leaseManager.grantLease(1L, listOf(addr("cs-1")))
        leaseManager.revokeLease(1L)

        assertNull(leaseManager.getActiveLease(1L))
        assertFalse(leaseManager.hasValidLease(1L))
    }

    @Test
    fun `hasValidLease returns true for active lease`() {
        leaseManager.grantLease(1L, listOf(addr("cs-1")))
        assertTrue(leaseManager.hasValidLease(1L))
    }

    @Test
    fun `hasValidLease returns false for no lease`() {
        assertFalse(leaseManager.hasValidLease(999L))
    }
}

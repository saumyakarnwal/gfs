package gfs.master.chunkserver

import gfs.common.GfsConfig
import gfs.proto.ChunkServerAddress
import java.util.concurrent.ConcurrentHashMap

class InMemoryChunkServerRegistry : ChunkServerRegistry {

    private val servers = ConcurrentHashMap<String, ChunkServerInfo>()

    override fun register(serverId: String, endpoint: String, availableBytes: Long): ChunkServerInfo {
        val info = ChunkServerInfo(
            serverId = serverId,
            endpoint = endpoint,
            lastHeartbeat = System.currentTimeMillis(),
            availableBytes = availableBytes
        )
        servers[serverId] = info
        return info
    }

    override fun updateHeartbeat(serverId: String, chunkHandles: Set<Long>, availableBytes: Long) {
        val info = servers[serverId] ?: return
        info.lastHeartbeat = System.currentTimeMillis()
        info.availableBytes = availableBytes
        info.chunks.clear()
        info.chunks.addAll(chunkHandles)
    }

    override fun addChunkLocation(serverId: String, chunkHandle: Long) {
        val info = servers[serverId] ?: return
        info.chunks.add(chunkHandle)
    }

    override fun getLocationsForChunk(handle: Long): List<ChunkServerAddress> {
        return servers.values
            .filter { handle in it.chunks }
            .map { chunkServerAddress(it) }
    }

    override fun selectServersForNewChunk(replicationFactor: Int, exclude: Set<String>): List<ChunkServerAddress> {
        return servers.values
            .filter { it.serverId !in exclude }
            .filter { isAlive(it.serverId) }
            .sortedByDescending { it.availableBytes }
            .take(replicationFactor)
            .map { chunkServerAddress(it) }
    }

    override fun isAlive(serverId: String): Boolean {
        val info = servers[serverId] ?: return false
        val elapsed = System.currentTimeMillis() - info.lastHeartbeat
        return elapsed < GfsConfig.HEARTBEAT_INTERVAL_MS * GfsConfig.HEARTBEAT_MISS_THRESHOLD
    }

    override fun getServerInfo(serverId: String): ChunkServerInfo? = servers[serverId]

    override fun getAllServers(): Collection<ChunkServerInfo> = servers.values.toList()

    private fun chunkServerAddress(info: ChunkServerInfo): ChunkServerAddress {
        return ChunkServerAddress.newBuilder()
            .setServerId(info.serverId)
            .setEndpoint(info.endpoint)
            .build()
    }
}

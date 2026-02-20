package gfs.master.chunkserver

import gfs.proto.ChunkServerAddress

interface ChunkServerRegistry {

    fun register(serverId: String, endpoint: String, availableBytes: Long): ChunkServerInfo

    fun updateHeartbeat(serverId: String, chunkHandles: Set<Long>, availableBytes: Long)

    fun addChunkLocation(serverId: String, chunkHandle: Long)

    fun getLocationsForChunk(handle: Long): List<ChunkServerAddress>

    fun selectServersForNewChunk(replicationFactor: Int, exclude: Set<String> = emptySet()): List<ChunkServerAddress>

    fun isAlive(serverId: String): Boolean

    fun getServerInfo(serverId: String): ChunkServerInfo?

    fun getAllServers(): Collection<ChunkServerInfo>
}

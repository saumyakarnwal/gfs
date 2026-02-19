package gfs.master.chunkserver

data class ChunkServerInfo(
    val serverId: String,
    val endpoint: String,
    var lastHeartbeat: Long,
    var availableBytes: Long,
    val chunks: MutableSet<Long> = mutableSetOf()
)

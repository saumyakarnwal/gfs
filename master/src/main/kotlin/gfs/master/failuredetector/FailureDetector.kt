package gfs.master.failuredetector

import gfs.common.GfsConfig
import gfs.master.chunk.ChunkManager
import gfs.master.chunkserver.ChunkServerRegistry
import gfs.master.namespace.NamespaceTree
import gfs.proto.ChunkServerAddress
import kotlinx.coroutines.*
import java.util.logging.Logger

class FailureDetector(
    private val chunkServerRegistry: ChunkServerRegistry,
    private val chunkManager: ChunkManager,
    private val namespaceTree: NamespaceTree,
    private val onReReplicationNeeded: (Long, Int) -> Unit
) {
    private val logger = Logger.getLogger(FailureDetector::class.java.name)
    private var scanJob: Job? = null

    fun start(scope: CoroutineScope) {
        scanJob = scope.launch {
            while (isActive) {
                delay(GfsConfig.HEARTBEAT_INTERVAL_MS * 2)
                scan()
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
    }

    fun scan() {
        val allServers = chunkServerRegistry.getAllServers()
        val deadServers = allServers.filter { !chunkServerRegistry.isAlive(it.serverId) }

        for (server in deadServers) {
            logger.warning("ChunkServer ${server.serverId} is dead (no heartbeat)")

            for (chunkHandle in server.chunks.toSet()) {
                val remainingLocations = chunkServerRegistry.getLocationsForChunk(chunkHandle)
                    .filter { it.serverId != server.serverId }

                val meta = chunkManager.getChunkMetadata(chunkHandle) ?: continue
                val node = namespaceTree.getNode(meta.filePath) ?: continue
                val desired = node.replicationFactor

                val deficit = desired - remainingLocations.size
                if (deficit > 0) {
                    logger.info(
                        "Chunk $chunkHandle under-replicated: " +
                            "${remainingLocations.size}/$desired replicas remaining"
                    )
                    onReReplicationNeeded(chunkHandle, deficit)
                }
            }
        }
    }
}

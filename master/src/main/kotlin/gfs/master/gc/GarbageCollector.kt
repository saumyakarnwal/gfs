package gfs.master.gc

import gfs.common.GfsConfig
import gfs.master.chunk.ChunkManager
import gfs.master.namespace.NamespaceTree
import kotlinx.coroutines.*
import java.util.logging.Logger

class GarbageCollector(
    private val namespaceTree: NamespaceTree,
    private val chunkManager: ChunkManager
) {
    private val logger = Logger.getLogger(GarbageCollector::class.java.name)
    private var gcJob: Job? = null

    companion object {
        const val HIDDEN_PREFIX = "/.hidden/"
    }

    fun start(scope: CoroutineScope) {
        gcJob = scope.launch {
            while (isActive) {
                delay(GfsConfig.GC_INTERVAL_MS)
                collectGarbage()
            }
        }
    }

    fun stop() {
        gcJob?.cancel()
    }

    fun collectGarbage() {
        val allNodes = namespaceTree.getAllNodes()
        val now = System.currentTimeMillis()

        // Find hidden files that have expired
        val expiredHidden = allNodes.filter { (path, node) ->
            path.startsWith(HIDDEN_PREFIX) &&
                !node.isDirectory &&
                (now - node.modifiedAt) > GfsConfig.GC_HIDDEN_FILE_TTL_MS
        }

        for ((path, _) in expiredHidden) {
            try {
                chunkManager.removeChunksForFile(path)
                namespaceTree.delete(path)
                logger.info("GC: collected hidden file $path")
            } catch (e: Exception) {
                logger.warning("GC: failed to collect $path: ${e.message}")
            }
        }

        // Find orphaned chunks (chunks with no owning file)
        val allFileChunks = allNodes.values
            .filter { !it.isDirectory }
            .flatMap { it.chunkHandles }
            .toSet()

        val allChunks = chunkManager.getAllChunks().map { it.handle }.toSet()
        val orphaned = allChunks - allFileChunks

        for (handle in orphaned) {
            chunkManager.getChunkMetadata(handle)?.let { meta ->
                chunkManager.removeChunksForFile(meta.filePath)
                logger.info("GC: removed orphaned chunk $handle (was file ${meta.filePath})")
            }
        }
    }

    fun markForDeletion(originalPath: String): String {
        val hiddenPath = "$HIDDEN_PREFIX${System.currentTimeMillis()}_${originalPath.replace("/", "_")}"
        return hiddenPath
    }
}

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

    fun ensureHiddenDirectory() {
        if (!namespaceTree.exists(HIDDEN_PREFIX.trimEnd('/'))) {
            try {
                namespaceTree.createDirectory(HIDDEN_PREFIX.trimEnd('/'))
            } catch (_: Exception) {
                // Already exists (race condition) — fine
            }
        }
    }

    fun start(scope: CoroutineScope) {
        ensureHiddenDirectory()
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

        for ((path, node) in expiredHidden) {
            try {
                // Decrement ref counts using chunk handles from the namespace node.
                // The chunk manager's file-to-chunk mapping uses the original file path,
                // not the hidden path, so we must work with handles directly.
                for (handle in node.chunkHandles) {
                    val newRefCount = chunkManager.decrementRefCount(handle)
                    if (newRefCount <= 0) {
                        chunkManager.removeChunk(handle)
                    }
                }
                namespaceTree.delete(path)
                logger.info("GC: collected hidden file $path")
            } catch (e: Exception) {
                logger.warning("GC: failed to collect $path: ${e.message}")
            }
        }

        // Find orphaned chunks (chunks not referenced by any file)
        val allFileChunks = allNodes.values
            .filter { !it.isDirectory }
            .flatMap { it.chunkHandles }
            .toSet()

        val allChunks = chunkManager.getAllChunks()
        val orphaned = allChunks.filter { it.handle !in allFileChunks && it.referenceCount <= 0 }

        for (chunk in orphaned) {
            chunkManager.removeChunk(chunk.handle)
            logger.info("GC: removed orphaned chunk ${chunk.handle} (was file ${chunk.filePath})")
        }
    }

    fun markForDeletion(originalPath: String): String {
        ensureHiddenDirectory()
        val hiddenPath = "$HIDDEN_PREFIX${System.currentTimeMillis()}_${originalPath.replace("/", "_")}"
        return hiddenPath
    }
}

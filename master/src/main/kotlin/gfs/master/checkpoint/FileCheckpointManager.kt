package gfs.master.checkpoint

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class FileCheckpointManager(private val checkpointDir: Path) : CheckpointManager {

    private val json = Json { prettyPrint = true }

    init {
        Files.createDirectories(checkpointDir)
    }

    override fun saveCheckpoint(state: MasterState) {
        val fileName = "checkpoint-${state.lastSequenceNumber}"
        val tempFile = checkpointDir.resolve("$fileName.tmp")
        val finalFile = checkpointDir.resolve("$fileName.json")

        val content = json.encodeToString(MasterState.serializer(), state)
        Files.writeString(tempFile, content)
        Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun loadLatestCheckpoint(): MasterState? {
        val latestFile = findLatestCheckpointFile() ?: return null
        val content = Files.readString(latestFile)
        return json.decodeFromString(MasterState.serializer(), content)
    }

    override fun getCheckpointSequenceNumber(): Long? {
        val latestFile = findLatestCheckpointFile() ?: return null
        return extractSequenceNumber(latestFile)
    }

    private fun findLatestCheckpointFile(): Path? {
        if (!Files.exists(checkpointDir)) return null
        return Files.list(checkpointDir)
            .filter { it.fileName.toString().startsWith("checkpoint-") && it.fileName.toString().endsWith(".json") }
            .max(Comparator.comparingLong { extractSequenceNumber(it) })
            .orElse(null)
    }

    private fun extractSequenceNumber(path: Path): Long {
        val name = path.fileName.toString()
        return name.removePrefix("checkpoint-").removeSuffix(".json").toLong()
    }
}

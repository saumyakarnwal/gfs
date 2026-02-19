package gfs.master.checkpoint

interface CheckpointManager {

    fun saveCheckpoint(state: MasterState)

    fun loadLatestCheckpoint(): MasterState?

    fun getCheckpointSequenceNumber(): Long?
}

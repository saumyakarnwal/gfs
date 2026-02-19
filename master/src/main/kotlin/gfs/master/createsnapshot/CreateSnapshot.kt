package gfs.master.createsnapshot

import gfs.master.service.status
import gfs.proto.*

class CreateSnapshot {

    fun execute(request: CreateSnapshotRequest): CreateSnapshotResponse {
        // Stub — snapshot (copy-on-write) is Phase 5
        return CreateSnapshotResponse.newBuilder()
            .setStatus(status(StatusCode.INTERNAL_ERROR, "Snapshot not yet implemented"))
            .build()
    }
}

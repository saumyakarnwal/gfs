package gfs.master.service

import gfs.master.createfile.CreateFile
import gfs.master.createdirectory.CreateDirectory
import gfs.master.createsnapshot.CreateSnapshot
import gfs.master.deletefile.DeleteFile
import gfs.master.getfileinfo.GetFileInfo
import gfs.master.getchunklocations.GetChunkLocations
import gfs.master.getwritetarget.GetWriteTarget
import gfs.master.listdirectory.ListDirectory
import gfs.master.renamefile.RenameFile
import gfs.master.setreplication.SetReplication
import gfs.proto.*

class MasterService(
    private val createFile: CreateFile,
    private val deleteFile: DeleteFile,
    private val renameFile: RenameFile,
    private val createDirectory: CreateDirectory,
    private val listDirectory: ListDirectory,
    private val getFileInfo: GetFileInfo,
    private val getChunkLocations: GetChunkLocations,
    private val getWriteTarget: GetWriteTarget,
    private val createSnapshot: CreateSnapshot,
    private val setReplication: SetReplication
) : MasterServiceGrpcKt.MasterServiceCoroutineImplBase() {

    override suspend fun createFile(request: CreateFileRequest) = methodImpl {
        createFile.execute(request)
    }

    override suspend fun deleteFile(request: DeleteFileRequest) = methodImpl {
        deleteFile.execute(request)
    }

    override suspend fun renameFile(request: RenameFileRequest) = methodImpl {
        renameFile.execute(request)
    }

    override suspend fun createDirectory(request: CreateDirectoryRequest) = methodImpl {
        createDirectory.execute(request)
    }

    override suspend fun listDirectory(request: ListDirectoryRequest) = methodImpl {
        listDirectory.execute(request)
    }

    override suspend fun getFileInfo(request: GetFileInfoRequest) = methodImpl {
        getFileInfo.execute(request)
    }

    override suspend fun getChunkLocations(request: GetChunkLocationsRequest) = methodImpl {
        getChunkLocations.execute(request)
    }

    override suspend fun getWriteTarget(request: GetWriteTargetRequest) = methodImpl {
        getWriteTarget.execute(request)
    }

    override suspend fun createSnapshot(request: CreateSnapshotRequest) = methodImpl {
        createSnapshot.execute(request)
    }

    override suspend fun setReplication(request: SetReplicationRequest) = methodImpl {
        setReplication.execute(request)
    }
}

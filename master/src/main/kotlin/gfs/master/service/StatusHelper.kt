package gfs.master.service

// Re-export from common for backward compatibility within the master module
import gfs.proto.StatusCode

fun status(code: StatusCode, message: String? = null): gfs.proto.Status =
    gfs.common.grpc.status(code, message)

fun okStatus(message: String? = null): gfs.proto.Status =
    gfs.common.grpc.okStatus(message)

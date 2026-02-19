package gfs.common.grpc

import gfs.proto.StatusCode

fun status(code: StatusCode, message: String? = null): gfs.proto.Status {
    return gfs.proto.Status.newBuilder()
        .setCode(code)
        .setMessage(message ?: "")
        .build()
}

fun okStatus(message: String? = null): gfs.proto.Status = status(StatusCode.OK, message)

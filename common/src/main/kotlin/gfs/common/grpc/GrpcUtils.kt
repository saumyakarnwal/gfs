package gfs.common.grpc

import io.grpc.Status
import io.grpc.StatusException

suspend fun <T> methodImpl(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: StatusException) {
        throw e
    } catch (e: Exception) {
        throw StatusException(
            Status.INTERNAL.withDescription(e.message).withCause(e)
        )
    }
}

inline fun checkRequest(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription(lazyMessage()))
    }
}

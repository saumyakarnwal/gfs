package gfs.common.grpc

import io.grpc.Status
import io.grpc.StatusException
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("gfs.grpc")

suspend fun <T> methodImpl(block: suspend () -> T): T {
    try {
        return block()
    } catch (e: StatusException) {
        throw e
    } catch (t: Throwable) {
        logger.log(Level.SEVERE, "Unhandled exception in RPC handler", t)
        throw StatusException(
            Status.INTERNAL.withDescription(t.message).withCause(t)
        )
    }
}

inline fun checkRequest(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) {
        throw StatusException(Status.INVALID_ARGUMENT.withDescription(lazyMessage()))
    }
}

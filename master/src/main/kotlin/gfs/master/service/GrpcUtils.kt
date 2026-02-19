package gfs.master.service

// Re-export from common for backward compatibility within the master module
import gfs.common.grpc.methodImpl as commonMethodImpl
import gfs.common.grpc.checkRequest as commonCheckRequest

suspend fun <T> methodImpl(block: suspend () -> T): T = commonMethodImpl(block)

inline fun checkRequest(condition: Boolean, lazyMessage: () -> String) = commonCheckRequest(condition, lazyMessage)

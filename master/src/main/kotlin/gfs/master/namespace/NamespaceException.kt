package gfs.master.namespace

import gfs.proto.StatusCode

class NamespaceException(
    val statusCode: StatusCode,
    override val message: String
) : RuntimeException(message) {

    companion object {
        fun notFound(path: String) =
            NamespaceException(StatusCode.NOT_FOUND, "Path not found: $path")

        fun alreadyExists(path: String) =
            NamespaceException(StatusCode.ALREADY_EXISTS, "Path already exists: $path")

        fun directoryNotEmpty(path: String) =
            NamespaceException(StatusCode.INVALID_ARGUMENT, "Directory not empty: $path")
    }
}

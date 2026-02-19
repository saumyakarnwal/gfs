package gfs.master.namespace

object PathUtils {

    fun validate(path: String): Boolean {
        if (path == "/") return true
        return path.startsWith("/") &&
            !path.endsWith("/") &&
            !path.contains("//") &&
            path.split("/").drop(1).all { it.isNotEmpty() }
    }

    fun parentPath(path: String): String {
        require(path != "/") { "Root has no parent" }
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash == 0) "/" else path.substring(0, lastSlash)
    }

    fun fileName(path: String): String {
        require(path != "/") { "Root has no name" }
        return path.substringAfterLast('/')
    }

    fun components(path: String): List<String> {
        if (path == "/") return emptyList()
        return path.split("/").drop(1)
    }

    fun ancestorPaths(path: String): List<String> {
        if (path == "/") return emptyList()
        val parts = components(path)
        return parts.indices.map { i ->
            if (i == 0) "/" else "/" + parts.subList(0, i).joinToString("/")
        }
    }

    fun isRoot(path: String): Boolean = path == "/"
}

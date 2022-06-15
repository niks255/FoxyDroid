package nya.kitsunyan.foxydroid.installer

import java.io.File

interface InstallationEvents {
    suspend fun install(cacheFileName: String)

    suspend fun install(packageName: String, cacheFileName: String)

    suspend fun install(packageName: String, cacheFile: File)

    suspend fun uninstall(packageName: String)
}

package nya.kitsunyan.foxydroid.installer.utils

import android.net.Uri
import java.io.File

internal interface InstallationEvents {

	suspend fun install(cacheFileName: String)

	suspend fun install(packageName: String, cacheFileName: String)

	suspend fun install(packageName: String, cacheFile: File)

	suspend fun uninstall(packageName: String)

}
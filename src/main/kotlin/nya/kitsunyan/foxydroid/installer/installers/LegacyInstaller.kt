package nya.kitsunyan.foxydroid.installer.installers

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.installer.utils.BaseInstaller
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import java.io.File

class LegacyInstaller(context: Context) : BaseInstaller(context) {

	companion object {
		const val APK_MIME = "application/vnd.android.package-archive"
	}

	override suspend fun install(cacheFileName: String) {
		val cacheFile = Cache.getReleaseFile(context, cacheFileName)
		mLegacyInstaller(cacheFile)
	}

	override suspend fun install(packageName: String, cacheFileName: String) {
		val cacheFile = Cache.getReleaseFile(context, cacheFileName)
		mLegacyInstaller(cacheFile)
	}

	override suspend fun install(packageName: String, cacheFile: File) =
		mLegacyInstaller(cacheFile)

	private suspend fun mLegacyInstaller(file: File) {
		val (uri, flags) = if (Android.sdk(24)) {
			Pair(Cache.getReleaseUri(context, file.name),
				 Intent.FLAG_GRANT_READ_URI_PERMISSION)
		} else {
			Pair(Uri.fromFile(file), 0)
		}

		@Suppress("DEPRECATION")
		withContext(Dispatchers.IO) {
			context.startActivity(
				Intent(Intent.ACTION_INSTALL_PACKAGE)
					.setDataAndType(uri, APK_MIME)
					.setFlags(flags)
			)
		}
	}
}
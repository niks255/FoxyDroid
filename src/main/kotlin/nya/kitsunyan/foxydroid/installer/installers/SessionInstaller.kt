package nya.kitsunyan.foxydroid.installer.installers

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build.VERSION_CODES
import nya.kitsunyan.foxydroid.installer.InstallerService
import nya.kitsunyan.foxydroid.installer.utils.BaseInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import java.io.File

internal class SessionInstaller(context: Context) : BaseInstaller(context) {

	private val sessionInstaller = context.packageManager.packageInstaller
	private val intent = Intent(context, InstallerService::class.java)

	companion object {
		val flags = if (Android.sdk(31)) PendingIntent.FLAG_MUTABLE else 0
	}

	override suspend fun install(cacheFileName: String) {
		val cacheFile = Cache.getReleaseFile(context, cacheFileName)
		mSessionInstaller(cacheFile)
	}

	override suspend fun install(packageName: String, cacheFileName: String) {
		val cacheFile = Cache.getReleaseFile(context, cacheFileName)
		mSessionInstaller(cacheFile)
	}

	override suspend fun install(packageName: String, cacheFile: File) {
		mSessionInstaller(cacheFile)
	}

	private fun mSessionInstaller(cacheFile: File) {
		val sessionParams =
			PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
		if (Android.sdk(VERSION_CODES.S))
			sessionParams.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)

		val id = sessionInstaller.createSession(sessionParams)
		val session = sessionInstaller.openSession(id)

		session.use { activeSession ->
			activeSession.openWrite("package", 0, cacheFile.length()).use { packageStream ->
				cacheFile.inputStream().use { fileStream ->
					fileStream.copyTo(packageStream)
				}
			}

			val pendingIntent = PendingIntent.getService(context, id, intent, flags)
			session.commit(pendingIntent.intentSender)
		}
	}

	override suspend fun uninstall(packageName: String) = withContext(Dispatchers.IO) {
		intent.putExtra(InstallerService.KEY_ACTION, InstallerService.ACTION_UNINSTALL)
		val pendingIntent = PendingIntent.getService(context, -1, intent, flags)
		sessionInstaller.uninstall(packageName, pendingIntent.intentSender)
	}
}
package nya.kitsunyan.foxydroid.installer

import android.app.Service
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import nya.kitsunyan.foxydroid.NOTIFICATION_CHANNEL_DOWNLOADING
import nya.kitsunyan.foxydroid.NOTIFICATION_ID_DOWNLOADING
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.utility.extension.android.notificationManager

/**
 * Runs during or after a PackageInstaller session in order to handle completion, failure, or
 * interruptions requiring user intervention (e.g. "Install Unknown Apps" permission requests).
 */
class InstallerService : Service() {
	companion object {
		const val KEY_ACTION = "installerAction"
		const val ACTION_UNINSTALL = "uninstall"
	}

	override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

		if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
			// prompts user to enable unknown source
			val promptIntent: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)

			promptIntent?.let {
				it.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
				it.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending")
				it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

				startActivity(it)
			}
		} else {
			notifyStatus(intent)
		}

		stopSelf()
		return START_NOT_STICKY
	}

	/**
	 * Notifies user of installer outcome.
	 */
	private fun notifyStatus(intent: Intent) {
		// unpack from intent
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
		val name = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
		val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
		val installerAction = intent.getStringExtra(KEY_ACTION)

		// get application name for notifications
		val appLabel = try {
			if (name != null) packageManager.getApplicationLabel(
				packageManager.getApplicationInfo(
					name,
					PackageManager.GET_META_DATA
				)
			) else null
		} catch (_: Exception) {
			null
		}

		// start building
		val builder = NotificationCompat
			.Builder(this, NOTIFICATION_CHANNEL_DOWNLOADING)
			.setAutoCancel(true)

		when (status) {
			PackageInstaller.STATUS_SUCCESS -> {
				if (installerAction != ACTION_UNINSTALL) {
					Toast.makeText(
						this, java.lang.String.valueOf(appLabel) + " "
								+ getString(R.string.installed_toast), Toast.LENGTH_SHORT
					).show()
				}
			}
			PackageInstaller.STATUS_FAILURE_ABORTED -> {
				Toast.makeText(this, getString(R.string.install_canceled),
					Toast.LENGTH_SHORT).show()
			}
			PackageInstaller.STATUS_PENDING_USER_ACTION -> {}
			else -> {
				val title = if (installerAction == ACTION_UNINSTALL)
								R.string.unknown_error_uninstall.toString()
				            else
								R.string.unknown_error_install.toString()

				val notification = builder
					.setSmallIcon(android.R.drawable.stat_notify_error)
					.setContentTitle(title)
					.setContentText(message)
					.build()
				notificationManager.notify(
					NOTIFICATION_ID_DOWNLOADING,
					notification
				)
			}
		}
	}

	override fun onBind(intent: Intent?): IBinder? {
		return null
	}

}
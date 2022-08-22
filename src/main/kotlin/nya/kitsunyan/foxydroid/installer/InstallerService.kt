package nya.kitsunyan.foxydroid.installer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import nya.kitsunyan.foxydroid.*
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import nya.kitsunyan.foxydroid.utility.extension.android.notificationManager
import nya.kitsunyan.foxydroid.utility.extension.resources.getColorFromAttr

/**
 * Runs during or after a PackageInstaller session in order to handle completion, failure, or
 * interruptions requiring user intervention, such as the package installer prompt.
 */
class InstallerService : Service() {
    companion object {
        const val KEY_ACTION = "installerAction"
        const val KEY_APP_NAME = "appName"
        const val ACTION_UNINSTALL = "uninstall"
        private const val NOTIFICATION_TAG_PREFIX = "install-"
    }

    override fun onCreate() {
        super.onCreate()
        if (Android.sdk(26)) {
            NotificationChannel(
                NOTIFICATION_CHANNEL_INSTALLER,
                getString(R.string.syncing), NotificationManager.IMPORTANCE_LOW
            )
                .let(notificationManager::createNotificationChannel)
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)

        // only trigger a prompt if in foreground or below Android 10, otherwise make notification
        // launching a prompt in the background will fail silently
        if ((Utils.inForeground()) && status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Triggers the installer prompt and "unknown apps" prompt if needed
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
     * Notifies user of installer outcome. This can be success, error, or a request for user action
     * if installation cannot proceed automatically.
     *
     * @param intent provided by PackageInstaller to the callback service/activity.
     */
    private fun notifyStatus(intent: Intent) {
        // unpack from intent
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)

        // get package information from session
        val sessionInstaller = this.packageManager.packageInstaller
        val session = if (sessionId > 0) sessionInstaller.getSessionInfo(sessionId) else null
        val name = session?.appPackageName ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val installerAction = intent.getStringExtra(KEY_ACTION)

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

        val notificationTag = "download-$name"

        // start building
        val builder = NotificationCompat
            .Builder(this, NOTIFICATION_CHANNEL_INSTALLER)
            .setAutoCancel(true)
            .setColor(
                ContextThemeWrapper(this, R.style.Theme_Main_Light)
                    .getColorFromAttr(R.attr.colorPrimary).defaultColor
            )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // request user action with "downloaded" notification that triggers a working prompt
                notificationManager.notify(
                    notificationTag, NOTIFICATION_ID_INSTALLER, builder
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentIntent(installIntent(intent))
                        .setContentTitle(getString(R.string.downloaded_FORMAT, appLabel))
                        .setContentText(getString(R.string.tap_to_install_DESC))
                        .build()
                )
            }
            PackageInstaller.STATUS_SUCCESS -> {
                notificationManager.cancel(notificationTag, NOTIFICATION_ID_DOWNLOADING)
                Toast.makeText(this, java.lang.String.valueOf(appLabel) + " "
                        + getString(R.string.installed_toast), Toast.LENGTH_SHORT).show()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // do nothing if user cancels
            }
            else -> {
                // problem occurred when installing/uninstalling package
                val notification = builder
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle(getString(R.string.unknown_error_DESC))
                    .setContentText(message)
                    .build()
                notificationManager.notify(
                    notificationTag, NOTIFICATION_ID_INSTALLER, notification)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Generates an intent that provides the specified activity information necessary to trigger
     * the package manager's prompt, thus completing a staged installation requiring user
     * intervention.
     *
     * @param intent the intent provided by PackageInstaller to the callback target passed to
     * PackageInstaller.Session.commit().
     * @return a pending intent that can be attached to a background-accessible entry point such as
     * a notification
     */
    private fun installIntent(intent: Intent): PendingIntent {
        // prepare prompt intent
        val promptIntent : Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
        val name = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        return PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_INSTALL)
                .setData(Uri.parse("package:$name"))
                .putExtra(Intent.EXTRA_INTENT, promptIntent)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Android.sdk(23)) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

}
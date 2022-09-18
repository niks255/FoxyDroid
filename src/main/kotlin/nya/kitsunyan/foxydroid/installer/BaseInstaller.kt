package nya.kitsunyan.foxydroid.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nya.kitsunyan.foxydroid.utility.extension.android.Android

abstract class BaseInstaller(val context: Context) : InstallationEvents {
    companion object {
        const val ROOT_INSTALL_PACKAGE = "cat %s | pm install -i %s --user %s -t -r -S %s"
        const val ROOT_INSTALL_PACKAGE_SESSION_CREATE = "pm install-create -i %s --user %s -r -S %s"
        const val ROOT_INSTALL_PACKAGE_SESSION_WRITE = "cat %s | pm install-write -S %s %s %s"
        const val ROOT_INSTALL_PACKAGE_SESSION_COMMIT = "pm install-commit %s"
    }

    override suspend fun uninstall(packageName: String) {
        val uri = Uri.fromParts("package", packageName, null)
        val intent = Intent()
        intent.data = uri

        @Suppress("DEPRECATION")
        if (Android.sdk(28)) {
            intent.action = Intent.ACTION_DELETE
        } else {
            intent.action = Intent.ACTION_UNINSTALL_PACKAGE
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        withContext(Dispatchers.IO) { context.startActivity(intent) }
    }
}
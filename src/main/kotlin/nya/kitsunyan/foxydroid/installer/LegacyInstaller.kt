package nya.kitsunyan.foxydroid.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import java.io.File


// TODO: Use this for MIUI device instead of guiding new users
class LegacyInstaller(context: Context) : BaseInstaller(context) {
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

    override suspend fun uninstall(packageName: String) = mLegacyUninstaller(packageName)

    private suspend fun mLegacyInstaller(cacheFile: File) {
        val (uri, flags) = if (Android.sdk(24)) {
            Pair(
                Cache.getReleaseUri(context, cacheFile.name),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } else {
            Pair(Uri.fromFile(cacheFile), 0)
        }

        @Suppress("DEPRECATION")
        withContext(Dispatchers.IO) {
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .setFlags(flags)
            )
        }
    }

    private suspend fun mLegacyUninstaller(packageName: String) {
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
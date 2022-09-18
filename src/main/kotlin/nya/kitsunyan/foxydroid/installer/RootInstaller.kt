package nya.kitsunyan.foxydroid.installer

import android.content.Context
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.utility.extension.android.Android
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

class RootInstaller(context: Context) : BaseInstaller(context) {

    companion object {
        private val getCurrentUserState: String =
            if (Android.sdk(25)) Shell.su("am get-current-user").exec().out[0]
            else Shell.cmd("dumpsys activity | grep -E \"mUserLru\"")
                .exec().out[0].trim()
                .removePrefix("mUserLru: [").removeSuffix("]")

        private val String.quote
            get() = "\"${this.replace(Regex("""[\\$"`]""")) { c -> "\\${c.value}" }}\""

        val File.install
            get() = String.format(
                ROOT_INSTALL_PACKAGE,
                absolutePath,
                BuildConfig.APPLICATION_ID,
                getCurrentUserState,
                length()
            )

        val File.session_install_create
            get() = String.format(
                ROOT_INSTALL_PACKAGE_SESSION_CREATE,
                BuildConfig.APPLICATION_ID,
                getCurrentUserState,
                length()
            )

        fun File.sessionInstallWrite(session_id: Int) = String.format(
            ROOT_INSTALL_PACKAGE_SESSION_WRITE,
            absolutePath,
            length(),
            session_id,
            name
        )

        fun sessionInstallCommit(session_id: Int) = String.format(
            ROOT_INSTALL_PACKAGE_SESSION_COMMIT,
            session_id
        )
    }

    override suspend fun install(cacheFileName: String) {
        val cacheFile = Cache.getReleaseFile(context, cacheFileName)
        mRootInstaller(cacheFile)
    }

    override suspend fun install(packageName: String, cacheFileName: String) {
        val cacheFile = Cache.getReleaseFile(context, cacheFileName)
        mRootInstaller(cacheFile)
    }

    override suspend fun install(packageName: String, cacheFile: File) =
        mRootInstaller(cacheFile)

    private suspend fun mRootInstaller(cacheFile: File) {
        withContext(Dispatchers.Default) {
            if (Preferences[Preferences.Key.SilentInstall]) {
                Shell.cmd(cacheFile.session_install_create)
                    .submit {
                        val sessionIdPattern = Pattern.compile("(\\d+)")
                        val sessionIdMatcher = sessionIdPattern.matcher(it.out[0])

                        if (sessionIdMatcher.find()) {
                            val sessionId = sessionIdMatcher.group(1)?.toInt() ?: -1
                            Shell.cmd(cacheFile.sessionInstallWrite(sessionId))
                                .submit { Shell.cmd(sessionInstallCommit(sessionId)).exec() }
                        }
                    }

            } else { Shell.cmd(cacheFile.install).submit() }
        }
    }
}
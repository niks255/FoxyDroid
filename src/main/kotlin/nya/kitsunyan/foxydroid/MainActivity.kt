package nya.kitsunyan.foxydroid

import android.content.Intent
import android.content.pm.PackageInstaller
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.screen.ScreenActivity

class MainActivity: ScreenActivity() {
  companion object {
    const val ACTION_UPDATES = "${BuildConfig.APPLICATION_ID}.intent.action.UPDATES"
    const val ACTION_INSTALLED = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALLED"
    const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL"
    const val EXTRA_CACHE_FILE_NAME = "${BuildConfig.APPLICATION_ID}.intent.extra.CACHE_FILE_NAME"
  }

  override fun handleIntent(intent: Intent?) {
    when (intent?.action) {
      ACTION_UPDATES -> handleSpecialIntent(SpecialIntent.Updates)
      ACTION_INSTALLED -> handleSpecialIntent(SpecialIntent.Installed)
      ACTION_INSTALL -> handleSpecialIntent(
        SpecialIntent.Install(
          intent.packageName,
          intent.getStringExtra(EXTRA_CACHE_FILE_NAME)
        )
      )
      else -> super.handleIntent(intent)
    }
  }
}

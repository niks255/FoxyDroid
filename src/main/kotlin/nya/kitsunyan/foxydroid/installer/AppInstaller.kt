package nya.kitsunyan.foxydroid.installer

import android.content.Context
import nya.kitsunyan.foxydroid.content.Preferences
import nya.kitsunyan.foxydroid.installer.installers.*
import nya.kitsunyan.foxydroid.installer.utils.BaseInstaller

abstract class AppInstaller {
	abstract val defaultInstaller: BaseInstaller?

	companion object {
		@Volatile
		private var INSTANCE: AppInstaller? = null
		fun getInstance(context: Context?): AppInstaller? {
			if (INSTANCE == null) {
				synchronized(AppInstaller::class.java) {
					context?.let {
						INSTANCE = object : AppInstaller() {
							override val defaultInstaller: BaseInstaller
								get() {
									return if (Preferences[Preferences.Key.UseLegacyInstaller]) {
										LegacyInstaller(it)
									} else {
										SessionInstaller(it)
									}
								}
						}
					}
				}
			}
			return INSTANCE
		}
	}
}

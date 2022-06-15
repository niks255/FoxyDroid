package nya.kitsunyan.foxydroid.installer

import android.content.Context

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
                                    return DefaultInstaller(it)
                                }
                        }
                    }
                }
            }
            return INSTANCE
        }
    }
}

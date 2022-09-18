package nya.kitsunyan.foxydroid.utility

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
import android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
import android.content.Context
import android.content.pm.Signature
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.LocaleList
import android.provider.Settings
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.lifecycle.lifecycleScope
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.launch
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.database.Database
import nya.kitsunyan.foxydroid.entity.InstalledItem
import nya.kitsunyan.foxydroid.entity.Product
import nya.kitsunyan.foxydroid.entity.ProductItem
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.screen.ProductFragment
import nya.kitsunyan.foxydroid.screen.ProductsAdapter
import nya.kitsunyan.foxydroid.service.Connection
import nya.kitsunyan.foxydroid.service.DownloadService
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.utility.extension.text.*
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.util.Locale

object Utils {
  private fun createDefaultApplicationIcon(context: Context, tintAttrResId: Int): Drawable {
    return context.getDrawableCompat(R.drawable.ic_application_default).mutate()
      .apply { setTintList(context.getColorFromAttr(tintAttrResId)) }
  }

  fun getDefaultApplicationIcons(context: Context): Pair<Drawable, Drawable> {
    val progressIcon: Drawable = createDefaultApplicationIcon(context, android.R.attr.textColorSecondary)
    val defaultIcon: Drawable = createDefaultApplicationIcon(context, android.R.attr.colorAccent)
    return Pair(progressIcon, defaultIcon)
  }

  fun getToolbarIcon(context: Context, resId: Int): Drawable {
    val drawable = context.getDrawableCompat(resId).mutate()
    drawable.setTintList(context.getColorFromAttr(android.R.attr.textColorPrimary))
    return drawable
  }

  fun calculateHash(signature: Signature): String? {
    return MessageDigest.getInstance("MD5").digest(signature.toCharsString().toByteArray()).hex()
  }

  fun calculateFingerprint(certificate: Certificate): String {
    val encoded = try {
      certificate.encoded
    } catch (e: CertificateEncodingException) {
      null
    }
    return encoded?.let(::calculateFingerprint).orEmpty()
  }

  fun calculateFingerprint(key: ByteArray): String {
    return if (key.size >= 256) {
      try {
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key)
        val builder = StringBuilder()
        for (byte in fingerprint) {
          builder.append("%02X".format(Locale.US, byte.toInt() and 0xff))
        }
        builder.toString()
      } catch (e: Exception) {
        e.printStackTrace()
        ""
      }
    } else {
      ""
    }
  }

  fun startUpdate(packageName: String, installedItem: InstalledItem?, products: List<Pair<Product, Repository>>,
                  downloadConnection: Connection<DownloadService.Binder, DownloadService>
  ) {
    val productRepository = Product.findSuggested(products, installedItem) { it.first }
    val compatibleReleases = productRepository?.first?.selectedReleases.orEmpty()
      .filter { installedItem == null || installedItem.signature == it.signature }
    val release = if (compatibleReleases.size >= 2) {
      compatibleReleases
        .filter { it.platforms.contains(Android.primaryPlatform) }
        .minByOrNull { it.platforms.size }
        ?: compatibleReleases.minByOrNull { it.platforms.size }
        ?: compatibleReleases.firstOrNull()
    } else {
      compatibleReleases.firstOrNull()
    }
    val binder = downloadConnection.binder
    if (productRepository != null && release != null && binder != null) {
      binder.enqueue(
        packageName,
        productRepository.first.name,
        productRepository.second,
        release
      )
    } else Unit
  }

  fun configureLocale(context: Context): Context {
    val supportedLanguages = BuildConfig.LANGUAGES.toSet()
    val configuration = context.resources.configuration
    val currentLocales = if (Android.sdk(24)) {
      val localesList = configuration.locales
      (0 until localesList.size()).map(localesList::get)
    } else {
      @Suppress("DEPRECATION")
      listOf(configuration.locale)
    }
    val compatibleLocales = currentLocales
      .filter { it.language in supportedLanguages }
      .let { if (it.isEmpty()) listOf(Locale.US) else it }
    Locale.setDefault(compatibleLocales.first())
    val newConfiguration = Configuration(configuration)
    if (Android.sdk(24)) {
      newConfiguration.setLocales(LocaleList(*compatibleLocales.toTypedArray()))
    } else {
      @Suppress("DEPRECATION")
      newConfiguration.locale = compatibleLocales.first()
    }
    return context.createConfigurationContext(newConfiguration)
  }

  fun areAnimationsEnabled(context: Context): Boolean {
    return if (Android.sdk(26)) {
      ValueAnimator.areAnimatorsEnabled()
    } else {
      Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
  }

  /**
   * Checks if app is currently considered to be in the foreground by Android.
   */
  fun inForeground(): Boolean {
    val appProcessInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(appProcessInfo)
    val importance = appProcessInfo.importance
    return ((importance == IMPORTANCE_FOREGROUND) or (importance == IMPORTANCE_VISIBLE))
  }
}

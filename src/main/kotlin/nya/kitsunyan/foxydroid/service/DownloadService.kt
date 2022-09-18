package nya.kitsunyan.foxydroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat
import androidx.core.app.TaskStackBuilder
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import nya.kitsunyan.foxydroid.BuildConfig
import nya.kitsunyan.foxydroid.NOTIFICATION_CHANNEL_DOWNLOADING
import nya.kitsunyan.foxydroid.NOTIFICATION_ID_DOWNLOADING
import nya.kitsunyan.foxydroid.NOTIFICATION_ID_SYNCING
import nya.kitsunyan.foxydroid.MainActivity
import nya.kitsunyan.foxydroid.MainActivity.Companion.EXTRA_CACHE_FILE_NAME
import nya.kitsunyan.foxydroid.R
import nya.kitsunyan.foxydroid.content.Cache
import nya.kitsunyan.foxydroid.entity.Release
import nya.kitsunyan.foxydroid.entity.Repository
import nya.kitsunyan.foxydroid.installer.AppInstaller
import nya.kitsunyan.foxydroid.network.Downloader
import nya.kitsunyan.foxydroid.utility.Utils
import nya.kitsunyan.foxydroid.utility.extension.android.*
import nya.kitsunyan.foxydroid.utility.extension.resources.*
import nya.kitsunyan.foxydroid.utility.extension.text.*
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

class DownloadService : ConnectionService<DownloadService.Binder>() {
  companion object {
    private const val ACTION_OPEN = "${BuildConfig.APPLICATION_ID}.intent.action.OPEN"
    private const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL"
    private const val ACTION_CANCEL = "${BuildConfig.APPLICATION_ID}.intent.action.CANCEL"
    private val mutableDownloadState = MutableSharedFlow<State.Downloading>()
    private val downloadState = mutableDownloadState.asSharedFlow()
  }

  class Receiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val action = intent.action.orEmpty()
      when {
        action.startsWith("$ACTION_OPEN.") -> {
          val packageName = action.substring(ACTION_OPEN.length + 1)
          context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setData(Uri.parse("package:$packageName"))
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        action.startsWith("$ACTION_INSTALL.") -> {
          val packageName = action.substring(ACTION_INSTALL.length + 1)
          val cacheFileName = intent.getStringExtra(EXTRA_CACHE_FILE_NAME)
          context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_INSTALL).setData(Uri.parse("package:$packageName"))
            .putExtra(EXTRA_CACHE_FILE_NAME, cacheFileName)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
      }
    }
  }

  private val scope = CoroutineScope(Dispatchers.Default)
  private val mainDispatcher = Dispatchers.Main

  sealed class State(val packageName: String, val name: String) {
    class Pending(packageName: String, name: String) : State(packageName, name)
    class Connecting(packageName: String, name: String) : State(packageName, name)
    class Downloading(packageName: String, name: String, val read: Long, val total: Long?) :
      State(packageName, name)

    class Success(
      packageName: String, name: String, val release: Release
    ) : State(packageName, name)

    class Error(packageName: String, name: String) : State(packageName, name)
    class Cancel(packageName: String, name: String) : State(packageName, name)
  }

  private val mutableStateSubject = MutableSharedFlow<State>()

  private class Task(
    val packageName: String, val name: String, val release: Release,
    val url: String, val authentication: String,
  ) {
    val notificationTag: String
      get() = "download-$packageName"
  }

  private data class CurrentTask(val task: Task, val disposable: Disposable, val lastState: State)

  private var started = false
  private val tasks = mutableListOf<Task>()
  private var currentTask: CurrentTask? = null

  inner class Binder : android.os.Binder() {
    val stateSubject = mutableStateSubject.asSharedFlow()

    fun enqueue(packageName: String, name: String, repository: Repository, release: Release) {
      val task = Task(
        packageName,
        name,
        release,
        release.getDownloadUrl(repository),
        repository.authentication
      )
      if (Cache.getReleaseFile(this@DownloadService, release.cacheFileName).exists()) {
        publishSuccess(task)
      } else {
        cancelTasks(packageName)
        cancelCurrentTask(packageName)
        notificationManager.cancel(task.notificationTag, NOTIFICATION_ID_DOWNLOADING)
        tasks += task
        if (currentTask == null) {
          handleDownload()
        } else {
          scope.launch { mutableStateSubject.emit(State.Pending(packageName, name)) }
        }
      }
    }

    fun cancel(packageName: String) {
      cancelTasks(packageName)
      cancelCurrentTask(packageName)
      handleDownload()
    }
  }

  private val binder = Binder()
  override fun onBind(intent: Intent): Binder = binder

  override fun onCreate() {
    super.onCreate()

    if (Android.sdk(26)) {
      NotificationChannel(
        NOTIFICATION_CHANNEL_DOWNLOADING,
        getString(R.string.downloading), NotificationManager.IMPORTANCE_LOW
      )
        .apply { setShowBadge(false) }
        .let(notificationManager::createNotificationChannel)
    }

    downloadState.onEach { publishForegroundState(false, it) }.launchIn(scope)
  }

  override fun onDestroy() {
    super.onDestroy()

    scope.cancel()
    cancelTasks(null)
    cancelCurrentTask(null)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      currentTask?.let { binder.cancel(it.task.packageName) }
    }
    return START_NOT_STICKY
  }

  private fun cancelTasks(packageName: String?) {
    tasks.removeAll {
      (packageName == null || it.packageName == packageName) && run {
        scope.launch(mainDispatcher) {
          mutableStateSubject.emit(
            State.Cancel(
              it.packageName,
              it.name
            )
          )
        }
        true
      }
    }
  }

  private fun cancelCurrentTask(packageName: String?) {
    currentTask?.let {
      if (packageName == null || it.task.packageName == packageName) {
        currentTask = null
        scope.launch(mainDispatcher) {
          mutableStateSubject.emit(
            State.Cancel(
              it.task.packageName,
              it.task.name
            )
          )
        }
        it.disposable.dispose()
      }
    }
  }

  private enum class ValidationError { INTEGRITY, FORMAT, METADATA, SIGNATURE, PERMISSIONS }

  private sealed class ErrorType {
    object Network : ErrorType()
    object Http : ErrorType()
    class Validation(val validateError: ValidationError) : ErrorType()
  }

  private fun showNotificationInstall(task: Task) {
    val intent = Intent(this, MainActivity::class.java)
      .setAction(MainActivity.ACTION_INSTALL)
      .setData(Uri.parse("package:$task.packageName"))
      .putExtra(EXTRA_CACHE_FILE_NAME, task.release.cacheFileName)
      .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val resultPendingIntent: PendingIntent? = TaskStackBuilder.create(this).run {
      addNextIntentWithParentStack(intent)
      getPendingIntent(
        0,
        if (Android.sdk(31)) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
      )
    }
    notificationManager.notify(
      task.notificationTag, NOTIFICATION_ID_DOWNLOADING, NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_DOWNLOADING)
        .setAutoCancel(true)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setColor(
          ContextThemeWrapper(this, R.style.Theme_Main_Light)
            .getColorFromAttr(R.attr.colorPrimary).defaultColor
        )
        .setContentIntent(resultPendingIntent)
        .setContentTitle(getString(R.string.downloaded_FORMAT, task.name))
        .setContentText(getString(R.string.tap_to_install_DESC))
        .build()
    )
  }

  private fun showNotificationError(task: Task, errorType: ErrorType) {
    notificationManager.notify(task.notificationTag,
      NOTIFICATION_ID_DOWNLOADING,
      NotificationCompat
        .Builder(this, NOTIFICATION_CHANNEL_DOWNLOADING)
        .setAutoCancel(true)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setColor(
          ContextThemeWrapper(this, R.style.Theme_Main_Light)
            .getColorFromAttr(R.attr.colorPrimary).defaultColor
        )
        .setContentIntent(
          PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
              .setAction(Intent.ACTION_VIEW)
              .setData(Uri.parse("package:${task.packageName}"))
              .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            if (Android.sdk(23))
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else
              PendingIntent.FLAG_UPDATE_CURRENT
          )
        )
        .apply {
          when (errorType) {
            is ErrorType.Network -> {
              setContentTitle(
                getString(
                  R.string.could_not_download_FORMAT,
                  task.name
                )
              )
              setContentText(getString(R.string.network_error_DESC))
            }
            is ErrorType.Http -> {
              setContentTitle(
                getString(
                  R.string.could_not_download_FORMAT,
                  task.name
                )
              )
              setContentText(getString(R.string.http_error_DESC))
            }
            is ErrorType.Validation -> {
              setContentTitle(
                getString(
                  R.string.could_not_validate_FORMAT,
                  task.name
                )
              )
              setContentText(
                getString(
                  when (errorType.validateError) {
                    ValidationError.INTEGRITY -> R.string.integrity_check_error_DESC
                    ValidationError.FORMAT -> R.string.file_format_error_DESC
                    ValidationError.METADATA -> R.string.invalid_metadata_error_DESC
                    ValidationError.SIGNATURE -> R.string.invalid_signature_error_DESC
                    ValidationError.PERMISSIONS -> R.string.invalid_permissions_error_DESC
                  }
                )
              )
            }
          }::class
        }
        .build())
  }

  private fun publishSuccess(task: Task) {
    var consumed = false
    scope.launch(mainDispatcher) {
      mutableStateSubject.emit(State.Success(task.packageName, task.name, task.release))
      consumed = true
    }
    if (!consumed) {
      if (Utils.rootInstallerEnabled) {
        scope.launch {
          AppInstaller.getInstance(this@DownloadService)?.
                        defaultInstaller?.
                        install(task.release.cacheFileName)
        }
      } else {
        showNotificationInstall(task)
      }
    }
  }

  private fun validatePackage(task: Task, file: File): ValidationError? {
    val hash = try {
      val hashType = task.release.hashType.nullIfEmpty() ?: "SHA256"
      val digest = MessageDigest.getInstance(hashType)
      file.inputStream().use {
        val bytes = ByteArray(8 * 1024)
        generateSequence { it.read(bytes) }.takeWhile { it >= 0 }
          .forEach { digest.update(bytes, 0, it) }
        digest.digest().hex()
      }
    } catch (e: Exception) {
      ""
    }
    return if (hash.isEmpty() || hash != task.release.hash) {
      ValidationError.INTEGRITY
    } else {
      val packageInfo = try {
        packageManager.getPackageArchiveInfo(
          file.path,
          Android.PackageManager.signaturesFlag
        )
      } catch (e: Exception) {
        e.printStackTrace()
        null
      }
      if (packageInfo == null) {
        ValidationError.FORMAT
      } else if (packageInfo.packageName != task.packageName ||
        packageInfo.versionCodeCompat != task.release.versionCode
      ) {
        ValidationError.METADATA
      } else {
        val signature = packageInfo.singleSignature?.let(Utils::calculateHash).orEmpty()
        if (signature.isEmpty() || signature != task.release.signature) {
          ValidationError.SIGNATURE
        } else {
          val permissions =
            packageInfo.permissions?.asSequence().orEmpty().map { it.name }.toSet()
          if (!task.release.permissions.containsAll(permissions)) {
            ValidationError.PERMISSIONS
          } else {
            null
          }
        }
      }
    }
  }

  private val stateNotificationBuilder by lazy {
    NotificationCompat
      .Builder(this, NOTIFICATION_CHANNEL_DOWNLOADING)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setColor(
        ContextThemeWrapper(this, R.style.Theme_Main_Light)
          .getColorFromAttr(android.R.attr.colorPrimary).defaultColor
      )
      .addAction(
        0, getString(R.string.cancel), PendingIntent.getService(
          this,
          0,
          Intent(this, this::class.java).setAction(ACTION_CANCEL),
          if (Android.sdk(23))
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
          else
            PendingIntent.FLAG_UPDATE_CURRENT
        )
      )
  }

  private fun publishForegroundState(force: Boolean, state: State) {
    if (force || currentTask != null) {
      currentTask = currentTask?.copy(lastState = state)
      startForeground(NOTIFICATION_ID_SYNCING, stateNotificationBuilder.apply {
        when (state) {
          is State.Connecting -> {
            setContentTitle(getString(R.string.downloading_FORMAT, state.name))
            setContentText(getString(R.string.connecting))
            setProgress(1, 0, true)
          }
          is State.Downloading -> {
            setContentTitle(getString(R.string.downloading_FORMAT, state.name))
            if (state.total != null) {
              setContentText("${state.read.formatSize()} / ${state.total.formatSize()}")
              setProgress(100, (100f * state.read / state.total).roundToInt(), false)
            } else {
              setContentText(state.read.formatSize())
              setProgress(0, 0, true)
            }
          }
          is State.Pending, is State.Success, is State.Error, is State.Cancel -> {
            throw IllegalStateException()
          }
        }::class
      }.build())
      scope.launch { mutableStateSubject.emit(state) }
    }
  }

  private fun handleDownload() {
    if (currentTask == null) {
      if (tasks.isNotEmpty()) {
        val task = tasks.removeAt(0)
        if (!started) {
          started = true
          startSelf()
        }
        val initialState = State.Connecting(task.packageName, task.name)
        stateNotificationBuilder.setWhen(System.currentTimeMillis())
        publishForegroundState(true, initialState)
        val partialReleaseFile =
          Cache.getPartialReleaseFile(this, task.release.cacheFileName)
        lateinit var disposable: Disposable
        disposable = Downloader
          .download(
            task.url,
            partialReleaseFile,
            "",
            "",
            task.authentication
          ) { read, total ->
            if (!disposable.isDisposed) {
              scope.launch {
                mutableDownloadState.emit(
                  State.Downloading(
                    task.packageName,
                    task.name,
                    read,
                    total
                  )
                )
              }
            }
          }
          .observeOn(AndroidSchedulers.mainThread())
          .subscribe { result, throwable ->
            currentTask = null
            throwable?.printStackTrace()
            if (result == null || !result.success) {
              showNotificationError(
                task,
                if (result != null) ErrorType.Http else ErrorType.Network
              )
              scope.launch {
                mutableStateSubject.emit(
                  State.Error(
                    task.packageName,
                    task.name
                  )
                )
              }
            } else {
              val validationError = validatePackage(task, partialReleaseFile)
              if (validationError == null) {
                val releaseFile =
                  Cache.getReleaseFile(this, task.release.cacheFileName)
                partialReleaseFile.renameTo(releaseFile)
                publishSuccess(task)
              } else {
                partialReleaseFile.delete()
                showNotificationError(task, ErrorType.Validation(validationError))
                scope.launch {
                  mutableStateSubject.emit(
                    State.Error(
                      task.packageName,
                      task.name
                    )
                  )
                }
              }
            }
            handleDownload()
          }
        currentTask = CurrentTask(task, disposable, initialState)
      } else if (started) {
        started = false
        stopForeground(true)
        stopSelf()
      }
    }
  }
}

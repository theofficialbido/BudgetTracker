package com.bido.budgetsync.data

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bido.budgetsync.sync.UpdateOffer
import java.io.File
import java.io.IOException

/**
 * Installs the new build the laptop offers in its answer to a sync ("Update phone" in BaseCamp). The APK has the same
 * package name, signing key and a higher versionCode, so Android installs it over this app and keeps all its data.
 *
 * On Android 12+ an app may update itself without asking once it is its own installer of record, which it becomes
 * after the first update done this way. Until then (or on older Android) Android asks: the dialog opens straight away
 * when the app is on screen, otherwise an "Update ready" notification opens it.
 */
object SelfUpdater {
    private const val WORK = "self-update"
    private const val CHANNEL = "app-update"
    private const val NOTIFY_ID = 7301
    internal const val ACTION_STATUS = "com.bido.budgetsync.SELF_UPDATE_STATUS"
    private const val PREFS = "self_update"
    /** After asking once, the same build is not downloaded and offered again for this long. */
    private const val RETRY_MS = 60 * 60 * 1000L

    @Suppress("DEPRECATION")
    fun installed(context: Context): Pair<Long, String> {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return (if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()) to (p.versionName ?: "")
    }

    /** Called with the laptop's answer to each sync; starts the download if it offers something newer. */
    fun onOffer(context: Context, offer: UpdateOffer?) {
        if (offer == null || offer.versionCode <= installed(context).first) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong("code", 0) == offer.versionCode && System.currentTimeMillis() - prefs.getLong("at", 0) < RETRY_MS) return
        prefs.edit().putLong("code", offer.versionCode).putLong("at", System.currentTimeMillis()).apply()
        val request = OneTimeWorkRequestBuilder<SelfUpdateWorker>()
            .setInputData(workDataOf("code" to offer.versionCode, "name" to offer.versionName))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Streams the published APK from the laptop to [dest]. */
    internal suspend fun download(context: Context, dest: File) {
        val client = Syncer.connectedClient(context, force = true) ?: throw IOException("Laptop not reachable on this network")
        try {
            client.downloadApk(dest)
        } catch (e: SyncException) {
            if (e.kind == SyncException.Kind.UNREACHABLE) throw IOException(e.message) else throw e
        }
    }

    /** Hands [apk] to Android's package installer. The outcome arrives in [SelfUpdateReceiver]. */
    internal fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("app.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, SelfUpdateReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Android wants a tap: show its dialog now if the app is on screen, and leave a notification either way. */
    internal fun askUser(context: Context, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (inForeground()) runCatching { context.startActivity(confirm) }
        notify(
            context, "Budget Tracker update ready", "Tap to install the new version from your laptop.",
            PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    }

    internal fun failed(context: Context, message: String?) {
        Prefs(context).lastSyncMessage = "Update failed: ${message ?: "unknown error"}"
        notify(context, "Budget Tracker update failed", message ?: "The new version could not be installed.", null)
    }

    internal fun clearNotification(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFY_ID)

    private fun inForeground(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun notify(context: Context, title: String, text: String, tap: PendingIntent?) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_HIGH))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, n)
        } catch (_: SecurityException) {
            // Notifications are off; the dialog still opens the next time the app syncs while on screen.
        }
    }
}

class SelfUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val want = inputData.getLong("code", 0)
        val apk = File(ctx.cacheDir, "updates/app.apk")
        try {
            SelfUpdater.download(ctx, apk)
            // Only ever install this app, and only a newer build of it.
            @Suppress("DEPRECATION")
            val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0
            if (info?.packageName != ctx.packageName || code <= SelfUpdater.installed(ctx).first || code < want) {
                SelfUpdater.failed(ctx, "The laptop sent a file that is not a newer Budget Tracker build.")
                return@withContext Result.failure()
            }
            Prefs(ctx).lastSyncMessage = "Installing Budget Tracker ${inputData.getString("name").orEmpty()} from the laptop"
            SelfUpdater.install(ctx, apk)
            Result.success()
        } catch (e: IOException) {
            if (runAttemptCount < 3) Result.retry() else { SelfUpdater.failed(ctx, e.message); Result.failure() }
        } catch (e: Exception) {
            SelfUpdater.failed(ctx, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }
}

/** The installer's verdict. (The restart after an update is handled by [BootReceiver], which also syncs at once.) */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SelfUpdater.ACTION_STATUS -> when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { SelfUpdater.askUser(context, it) }
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // the user said not now; asked again later
                else -> SelfUpdater.failed(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            }
        }
    }
}

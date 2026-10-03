package com.bido.budgetsync.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The build published on the laptop. */
data class UpdateInfo(val versionCode: Long, val versionName: String)

sealed interface UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck
    data class UpToDate(val installed: String) : UpdateCheck
    data class Failed(val message: String) : UpdateCheck
}

/**
 * Updates the app from the laptop. The published APK has the same package name and signing key and a higher versionCode,
 * so Android installs it over the current app and keeps everything: settings, pairing token, queued entries, categories.
 */
object Updater {
    private const val FILE = "updates/app.apk"

    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): Pair<Long, String> {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return p.longVersionCode to (p.versionName ?: "")
    }

    suspend fun check(context: Context): UpdateCheck {
        val client = Syncer.connectedClient(context) ?: return UpdateCheck.Failed("Laptop not reachable on this network")
        val (installedCode, installedName) = installedVersion(context)
        return try {
            val (code, name) = withContext(Dispatchers.IO) { client.fetchVersion() }
            if (code > installedCode) UpdateCheck.Available(UpdateInfo(code, name)) else UpdateCheck.UpToDate("$installedName (build $installedCode)")
        } catch (e: SyncException) {
            UpdateCheck.Failed(if (e.message?.contains("404") == true) "No update published on the laptop yet" else e.message ?: "Update check failed")
        } catch (e: Exception) {
            UpdateCheck.Failed("Update check failed")
        }
    }

    /** Whether Android lets this app start an install. If not, the user must allow it once in system settings. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Downloads the published APK and opens Android's installer for it. Returns an error message, or null on success. */
    suspend fun downloadAndInstall(context: Context): String? {
        val client = Syncer.connectedClient(context) ?: return "Laptop not reachable on this network"
        val file = File(context.cacheDir, FILE)
        try {
            withContext(Dispatchers.IO) { client.downloadApk(file) }
        } catch (e: SyncException) {
            return e.message ?: "Download failed"
        } catch (e: Exception) {
            return "Download failed"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return null
    }
}

package com.yundac.apkcloner.install

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a built APK to Android's package installer.
 *
 * The clone cannot be installed silently: installing an APK that was not
 * obtained through a store needs the per-app "install unknown apps" grant, and
 * the system always shows a confirmation. What this class does is use the
 * PackageInstaller session API (which reports a real result back to us) with a
 * share/view intent as the fallback.
 */
object ApkInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, "Cannot open settings", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Starts an installer session for [apk]. Returns false when this device
     * cannot install packages without an additional user grant.
     */
    fun install(context: Context, apk: File, packageName: String): Boolean {
        if (!apk.isFile) return false
        if (!canInstall(context)) return false
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(packageName)

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(FILE_NAME, 0, apk.length()).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    session.fsync(output)
                }
                val resultIntent = Intent(context, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_PACKAGE, packageName)
                var flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The system fills in the status before delivering it.
                    flags = flags or android.app.PendingIntent.FLAG_MUTABLE
                }
                val pendingIntent = android.app.PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
            true
        }.getOrElse {
            Toast.makeText(context, "Installer session failed: ${it.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Opens the system "install an APK" UI for [apk] (fallback path). */
    fun view(context: Context, apk: File) {
        runCatching {
            val uri = fileUri(context, apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "No installer app available", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, apk: File) {
        runCatching {
            val uri = fileUri(context, apk)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = APK_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(apk.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share clone APK"))
        }.onFailure {
            Toast.makeText(context, "No app available to share the APK", Toast.LENGTH_SHORT).show()
        }
    }

    const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val FILE_NAME = "base.apk"

    private fun fileUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
}

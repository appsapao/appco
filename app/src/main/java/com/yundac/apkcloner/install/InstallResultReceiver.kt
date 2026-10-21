package com.yundac.apkcloner.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the package-installer result.
 *
 * When the system needs the user to confirm (the normal case for a sideloaded
 * APK), it delivers `STATUS_PENDING_USER_ACTION` together with a confirmation
 * Intent that must be started from an Activity context; this receiver forwards
 * it with `FLAG_ACTIVITY_NEW_TASK`.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirmation) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                lastResult = "Installed $packageName"
                Toast.makeText(context, "Clone installed", Toast.LENGTH_SHORT).show()
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                lastResult = "Install failed ($status): $message"
                Toast.makeText(context, "Install failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "com.yundac.apkcloner.extra.PACKAGE"

        @Volatile
        var lastResult: String? = null
            private set
    }
}

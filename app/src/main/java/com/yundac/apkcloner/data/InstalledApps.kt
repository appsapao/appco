package com.yundac.apkcloner.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/** An installed application that could be cloned. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String?,
    val apkPath: String,
    val icon: Drawable?,
    val isSystemApp: Boolean,
    /** Number of split APKs beyond the base APK; non-zero means "cannot clone". */
    val splitCount: Int,
    val sizeBytes: Long,
)

object InstalledApps {

    /**
     * Lists launchable applications. Runs on a background thread: loading icons
     * for a few hundred packages is far too slow for the main thread.
     */
    fun load(context: Context, includeSystemApps: Boolean = false): List<InstalledApp> {
        val packageManager = context.packageManager
        val packages = installedPackages(packageManager)
        val result = ArrayList<InstalledApp>(packages.size)

        for (packageInfo in packages) {
            val appInfo = packageInfo.applicationInfo ?: continue
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (!includeSystemApps && isSystem) continue
            if (packageInfo.packageName == context.packageName) continue
            val sourceDir = appInfo.sourceDir ?: continue
            if (!File(sourceDir).isFile) continue

            result.add(
                InstalledApp(
                    label = runCatching { appInfo.loadLabel(packageManager).toString() }
                        .getOrDefault(packageInfo.packageName),
                    packageName = packageInfo.packageName,
                    versionName = packageInfo.versionName,
                    apkPath = sourceDir,
                    icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull(),
                    isSystemApp = isSystem,
                    splitCount = appInfo.splitSourceDirs?.size ?: 0,
                    sizeBytes = File(sourceDir).length(),
                )
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }

    fun splitApksOf(context: Context, packageName: String): List<String> = runCatching {
        val info = packagedInfo(context.packageManager, packageName) ?: return emptyList()
        info.applicationInfo?.splitSourceDirs?.toList() ?: emptyList()
    }.getOrDefault(emptyList())

    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { packagedInfo(context.packageManager, packageName) != null }.getOrDefault(false)

    fun installedPackageNames(context: Context): Set<String> =
        installedPackages(context.packageManager).mapTo(HashSet()) { it.packageName }

    private fun installedPackages(packageManager: PackageManager): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

    private fun packagedInfo(packageManager: PackageManager, packageName: String): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
}

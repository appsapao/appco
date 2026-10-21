package com.yundac.apkcloner.core.clone

import com.yundac.apkcloner.core.apk.ApkMetadata

enum class Severity { BLOCKER, WARNING, INFO }

data class Finding(
    val severity: Severity,
    val code: String,
    val title: String,
    val detail: String,
)

/**
 * Pre-flight analysis of whether an APK can be cloned on this device, and what
 * will definitely not work afterwards.
 *
 * A blocker means the clone cannot be installed or cannot start; a warning means
 * it installs but some feature of the original app will be degraded. Nothing is
 * silently downgraded: every known limitation is reported before the clone is
 * built.
 */
object CompatibilityChecker {

    private val BILLING_PERMISSION = "com.android.vending.BILLING"
    private val PLAY_SERVICES_PREFIX = "com.google.android.gms"

    fun analyze(
        metadata: ApkMetadata,
        deviceSdkInt: Int,
        installedSplits: List<String> = emptyList(),
    ): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (metadata.splitName != null) {
            findings += Finding(
                Severity.BLOCKER,
                "SPLIT_APK",
                "App bundle / split APK",
                "This app is installed from split APKs (it declares the split '${metadata.splitName}'). " +
                    "Only the base APK is available, so the clone would be missing resources and native code " +
                    "and would crash on start. Install a standalone APK of this app to clone it.",
            )
        } else if (installedSplits.isNotEmpty()) {
            findings += Finding(
                Severity.BLOCKER,
                "SPLIT_APK",
                "App bundle / split APK",
                "Android reports ${installedSplits.size} additional split APK(s) for this app. " +
                    "Only the base APK can be read, so the clone would be missing resources and native code " +
                    "and would crash on start.",
            )
        }

        metadata.sharedUserId?.let { sharedUserId ->
            findings += Finding(
                Severity.BLOCKER,
                "SHARED_USER_ID",
                "Shares a Linux user ID",
                "The manifest declares android:sharedUserId=\"$sharedUserId\". Android refuses to install " +
                    "another package with that shared user ID unless it is signed with the same key, which a " +
                    "clone cannot be.",
            )
        }

        metadata.minSdk?.let { minSdk ->
            if (minSdk > deviceSdkInt) {
                findings += Finding(
                    Severity.BLOCKER,
                    "MIN_SDK_TOO_HIGH",
                    "Requires a newer Android version",
                    "The app requires API $minSdk but this device runs API $deviceSdkInt.",
                )
            }
        }

        if (metadata.apkSizeBytes > MAX_APK_BYTES) {
            findings += Finding(
                Severity.BLOCKER,
                "APK_TOO_LARGE",
                "APK is too large",
                "The APK is ${metadata.apkSizeBytes / (1024 * 1024)} MiB. Repackaging is limited to " +
                    "${MAX_APK_BYTES / (1024 * 1024)} MiB because signing maps ZIP offsets into 32-bit ints.",
            )
        }

        findings += Finding(
            Severity.WARNING,
            "SIGNATURE_CHANGED",
            "Signature changes",
            "The clone is signed with this app's key, not the original developer's. Android treats it as a " +
                "different app, so Google sign-in, Play Integrity/attestation, in-app billing, DRM, " +
                "purchases and any other feature that checks the signing certificate will fail.",
        )

        if (metadata.permissions.contains(BILLING_PERMISSION)) {
            findings += Finding(
                Severity.WARNING,
                "BILLING",
                "In-app purchases",
                "This app uses Google Play Billing. Purchases are tied to the original package name and " +
                    "signature, so they will not be available in the clone.",
            )
        }

        if (metadata.declaredPermissions.isNotEmpty()) {
            findings += Finding(
                Severity.INFO,
                "CUSTOM_PERMISSIONS",
                "Declares custom permissions",
                "The clone keeps ${metadata.declaredPermissions.size} custom permission name(s). They are " +
                    "global on Android, so they stay shared with the original app.",
            )
        }

        if (metadata.hasNativeLibraries) {
            findings += Finding(
                Severity.INFO,
                "NATIVE_LIBRARIES",
                "Contains native libraries",
                "Native libraries for ${metadata.nativeLibraryAbis.joinToString(", ")} are copied unchanged " +
                    "and keep 16 KiB page alignment. Libraries that hard-code the package name can still fail " +
                    "at runtime.",
            )
        }

        if (metadata.labelIsResourceReference) {
            findings += Finding(
                Severity.INFO,
                "LABEL_OVERRIDDEN",
                "App name is a string resource",
                "The clone's launcher name is set as a literal label on the application and its launcher " +
                    "activities, which overrides the original string resource without touching resources.arsc.",
            )
        }

        if (metadata.permissions.any { it.startsWith(PLAY_SERVICES_PREFIX) } ||
            metadata.minSdk == null
        ) {
            findings += Finding(
                Severity.INFO,
                "PLAY_SERVICES",
                "Uses Google Play services",
                "Components that rely on the Play services device check or on the original package name may " +
                    "malfunction in a clone.",
            )
        }

        return findings
    }

    fun blockers(findings: List<Finding>): List<Finding> = findings.filter { it.severity == Severity.BLOCKER }

    fun hasBlockers(findings: List<Finding>): Boolean = blockers(findings).isNotEmpty()

    private const val MAX_APK_BYTES = 1_900_000_000L

    /** Risk level used to colour the UI badge. */
    fun overallSeverity(findings: List<Finding>): Severity = when {
        findings.any { it.severity == Severity.BLOCKER } -> Severity.BLOCKER
        findings.any { it.severity == Severity.WARNING } -> Severity.WARNING
        else -> Severity.INFO
    }
}

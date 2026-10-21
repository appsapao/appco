package com.yundac.apkcloner.core.clone

import com.yundac.apkcloner.core.apk.ApkInspector
import com.yundac.apkcloner.core.apk.ApkIntegrity
import com.yundac.apkcloner.core.apk.ApkMetadata
import com.yundac.apkcloner.core.apk.ApkRepackager
import com.yundac.apkcloner.core.axml.AndroidAttr
import com.yundac.apkcloner.core.axml.ManifestEditor
import com.yundac.apkcloner.core.sign.ApkSignatureSchemeV2
import com.yundac.apkcloner.core.sign.ApkSignatureVerifier
import com.yundac.apkcloner.core.sign.ApkSigningKey
import java.io.File

/** Everything a single clone operation needs. */
data class CloneOptions(
    val sourceApk: File,
    val outputApk: File,
    val workingDirectory: File,
    val newPackageName: String,
    val newLabel: String,
    /** Framework permissions to strip from the manifest. */
    val permissionsToRemove: Set<String> = emptySet(),
    /** Adds `android:allowBackup="false"` to the clone. */
    val disableBackup: Boolean = false,
    /**
     * Split APKs that the *installed* package has, as reported by PackageManager.
     *
     * A base APK alone does not declare that it belongs to an app bundle, so the
     * manifest cannot reveal this; without this hint the pipeline would happily
     * produce a clone that is missing most of its resources and native code.
     */
    val installedSplits: List<String> = emptyList(),
)

/** What the pipeline actually changed, reported back to the UI. */
data class CloneChanges(
    val sourcePackage: String,
    val newPackage: String,
    val label: String,
    val removedPermissions: List<String>,
    val renamedAuthorities: List<Pair<String, String>>,
    val renamedLauncherLabels: List<String>,
    val allowBackupDisabled: Boolean,
    val repackagedEntryCount: Int,
)

/**
 * The end-to-end clone pipeline:
 *
 * 1. inspect the source APK,
 * 2. patch the binary manifest (package id, launcher label, provider authorities,
 *    removed permissions),
 * 3. rewrite the APK as an aligned, re-signed ZIP,
 * 4. sign it with APK Signature Scheme v2,
 * 5. re-read the result and verify signature, package name and every entry CRC.
 *
 * Step 5 is the reason this class exists as a unit-testable JVM component: a
 * clone is only reported as successful after it has been verified from disk.
 */
class ClonePipeline(private val signingKey: ApkSigningKey) {

    enum class Step { INSPECT, PATCH_MANIFEST, REPACKAGE, SIGN, VERIFY, DONE }

    data class Progress(val step: Step, val percent: Int, val message: String)

    data class Report(
        val outputApk: File,
        val changes: CloneChanges,
        val sourceMetadata: ApkMetadata,
        val outputMetadata: ApkMetadata,
        val signatureSubject: String?,
        val warnings: List<String>,
    )

    class CloneException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val inspector = ApkInspector()
    private val repackager = ApkRepackager()

    fun clone(
        options: CloneOptions,
        deviceSdkInt: Int,
        onProgress: (Progress) -> Unit = {},
    ): Report {
        PackageNames.problem(options.newPackageName)?.let {
            throw CloneException("Invalid target package name '${options.newPackageName}': $it")
        }
        if (options.newLabel.isBlank()) throw CloneException("The clone needs a non-empty name")
        if (!options.sourceApk.isFile) throw CloneException("Source APK not found: ${options.sourceApk}")
        if (options.installedSplits.isNotEmpty()) {
            throw CloneException(
                "This app is installed from split APKs (${options.installedSplits.size} additional APK(s)). " +
                    "Only the base APK is available, so a clone would be missing resources and native code " +
                    "and would crash on start."
            )
        }
        if (options.sourceApk.canonicalFile == options.outputApk.canonicalFile) {
            throw CloneException("Source and output APK are the same file")
        }
        options.workingDirectory.mkdirs()
        options.outputApk.parentFile?.mkdirs()

        onProgress(Progress(Step.INSPECT, 2, "Reading source APK"))
        val sourceMetadata = inspector.inspect(options.sourceApk)
        if (sourceMetadata.packageName == options.newPackageName) {
            throw CloneException("The clone must use a different package name than the source app")
        }
        val findings = CompatibilityChecker.analyze(sourceMetadata, deviceSdkInt)
        CompatibilityChecker.blockers(findings).firstOrNull()?.let {
            throw CloneException("This app cannot be cloned: ${it.title} -- ${it.detail}")
        }

        val manifestBytes = inspector.readManifestBytes(options.sourceApk)
        onProgress(Progress(Step.PATCH_MANIFEST, 10, "Patching AndroidManifest.xml"))
        val editor = ManifestEditor.parse(manifestBytes)
        val oldPackage = editor.renamePackage(options.newPackageName)
        val renamedAuthorities = editor.renameProviderAuthorities(oldPackage, options.newPackageName)
        editor.setApplicationLabel(options.newLabel)
        val launcherLabels = editor.setLauncherLabels(options.newLabel)
        val removedPermissions = editor.removePermissions(options.permissionsToRemove)
        var allowBackupDisabled = false
        if (options.disableBackup) {
            editor.setApplicationBoolean("allowBackup", AndroidAttr.ALLOW_BACKUP, false)
            allowBackupDisabled = true
        }
        val patchedManifest = editor.toBytes()

        val unsigned = File(options.workingDirectory, "unsigned.apk")
        onProgress(Progress(Step.REPACKAGE, 25, "Repackaging APK"))
        val repackageResult = repackager.repackage(
            source = options.sourceApk,
            output = unsigned,
            overrides = mapOf(ApkInspector.MANIFEST_NAME to patchedManifest),
            progress = { done, total ->
                val percent = 25 + (done * 40 / maxOf(total, 1))
                onProgress(Progress(Step.REPACKAGE, percent, "Repackaging entry $done/$total"))
            },
        )

        try {
            onProgress(Progress(Step.SIGN, 70, "Signing APK (v2 scheme)"))
            ApkSignatureSchemeV2.sign(unsigned, options.outputApk, signingKey)

            onProgress(Progress(Step.VERIFY, 85, "Verifying clone"))
            val verification = ApkSignatureVerifier.verify(options.outputApk)
            if (!verification.signatureValid) {
                options.outputApk.delete()
                throw CloneException(
                    "The clone failed signature verification and was discarded: " +
                        verification.errors.joinToString("; ")
                )
            }
            val integrity = ApkIntegrity.verify(options.outputApk)
            if (!integrity.isIntact) {
                options.outputApk.delete()
                throw CloneException(
                    "The clone has damaged ZIP entries and was discarded: " +
                        integrity.mismatches.joinToString("; ")
                )
            }
            val outputMetadata = inspector.inspect(options.outputApk)
            if (outputMetadata.packageName != options.newPackageName) {
                options.outputApk.delete()
                throw CloneException(
                    "Clone package name is '${outputMetadata.packageName}', expected '${options.newPackageName}'"
                )
            }

            onProgress(Progress(Step.DONE, 100, "Clone ready"))
            return Report(
                outputApk = options.outputApk,
                changes = CloneChanges(
                    sourcePackage = oldPackage,
                    newPackage = options.newPackageName,
                    label = options.newLabel,
                    removedPermissions = removedPermissions,
                    renamedAuthorities = renamedAuthorities,
                    renamedLauncherLabels = launcherLabels,
                    allowBackupDisabled = allowBackupDisabled,
                    repackagedEntryCount = repackageResult.entryCount,
                ),
                sourceMetadata = sourceMetadata,
                outputMetadata = outputMetadata,
                signatureSubject = verification.signerSubject,
                warnings = repackageResult.warnings,
            )
        } finally {
            unsigned.delete()
        }
    }
}

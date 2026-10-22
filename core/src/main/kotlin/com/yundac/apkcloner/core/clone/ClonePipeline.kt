package com.yundac.apkcloner.core.clone

import com.yundac.apkcloner.core.apk.ApkInspector
import com.yundac.apkcloner.core.apk.ApkIntegrity
import com.yundac.apkcloner.core.apk.ApkMetadata
import com.yundac.apkcloner.core.apk.ApkRepackager
import com.yundac.apkcloner.core.axml.AndroidAttr
import com.yundac.apkcloner.core.axml.ManifestEditor
import com.yundac.apkcloner.core.sign.ApkSignatureVerifier
import com.yundac.apkcloner.core.sign.ApkSignatureSchemeV2
import com.yundac.apkcloner.core.sign.ApkSigningKey
import java.io.File

/** Everything the caller controls about one clone operation. */
data class CloneOptions(
    val sourceApk: File,
    val outputApk: File,
    val workingDirectory: File,
    val newPackageName: String,
    val newLabel: String,
    /** Permissions to strip from the clone (subset of the source's uses-permission). */
    val permissionsToRemove: Set<String> = emptySet(),
    /** Overrides `android:allowBackup` to false when true. */
    val disableBackup: Boolean = false,
    /**
     * Split APK paths that PackageManager reports for the installed source app.
     * A non-empty list blocks cloning outright: rewriting only the base APK would
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
    /** Custom permissions kept as declared: not owned by the clone or not safely remappable. */
    val keptPermissions: List<String> = emptyList(),
    /** Custom permissions remapped for the clone (declaration + uses move together). */
    val remappedPermissions: List<Pair<String, String>> = emptyList(),
    /** Relative manifest class names normalized against the original package. */
    val normalizedClassNames: List<Pair<String, String>> = emptyList(),
    val allowBackupDisabled: Boolean,
    val repackagedEntryCount: Int,
)

/**
 * The end-to-end clone pipeline:
 *
 * 1. inspect the source APK,
 * 2. patch the binary manifest (relative class names are normalized first, then
 *    custom-permission remapping, the package id, provider authorities, labels
 *    and stripped permissions),
 * 3. rewrite the APK as an aligned ZIP into a *temporary* staging file,
 * 4. sign it with APK Signature Scheme v2,
 * 5. verify signature, integrity, package name, label and authorities from the
 *    staging file on disk,
 * 6. atomically replace [CloneOptions.outputApk] -- only after all checks pass.
 *
 * Because steps 3-5 never touch the destination, a failed rebuild cannot delete
 * or corrupt a previously saved clone at the same path. Step 5 is also the
 * reason this class exists as a unit-testable JVM component: a clone is only
 * reported as successful after it has been verified from disk.
 */
class ClonePipeline(private val signingKey: ApkSigningKey) {

    enum class Step { INSPECT, PATCH_MANIFEST, REPACKAGE, SIGN, VERIFY, PUBLISH, DONE }

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
        installedDeclaredPermissions: Set<String> = emptySet(),
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

        // Everything below writes into a scratch file. The saved clone at
        // options.outputApk is replaced only once the scratch file has been
        // fully built, signed and verified.
        val staging = File(options.outputApk.absolutePath + ".build-" + uniqueToken() + ".partial")
        try {
            return buildIntoStaging(options, deviceSdkInt, installedDeclaredPermissions, onProgress, staging)
        } finally {
            // Success moved the file away; anything left here is a broken partial build.
            runCatching { if (staging.exists()) staging.delete() }
        }
    }

    private fun uniqueToken(): String =
        "${System.currentTimeMillis().toString(36)}-${(0 until 0xFFFFFF).random().toString(36)}"

    private fun buildIntoStaging(
        options: CloneOptions,
        deviceSdkInt: Int,
        installedDeclaredPermissions: Set<String>,
        onProgress: (Progress) -> Unit,
        staging: File,
    ): Report {
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
        val oldPackage = editor.packageName()
            ?: throw CloneException("Manifest declares no package name")

        // 1. Normalize relative class names BEFORE renaming the package: they
        //    resolve against the (soon replaced) `package` attribute, while the
        //    DEX still declares them inside the original package.
        val normalizedNames = editor.normalizeRelativeClassNames(oldPackage)

        // 2. Custom-permission ownership check and consistent mapping, before
        //    building. Cases that cannot be remapped safely abort the clone.
        val permissionRenames = editor.planPermissionRemapping(
            originalPackage = oldPackage,
            newPackage = options.newPackageName,
            installedDeclaredPermissions = installedDeclaredPermissions,
        )
        val remappedPermissions = editor.applyPermissionRemapping(permissionRenames)

        // 3. Package rename (repoints only the package attribute's string slot;
        //    the shared pool entry keeps its content for every other reference).
        editor.renamePackage(options.newPackageName)

        // 4. Every provider authority is renamed -- including ones unrelated to
        //    the package prefix, which collide just the same at install time.
        val cloneSuffix = PackageNames.suggestSuffix(cloneIndexOf(options.newPackageName, oldPackage))
        val renamedAuthorities = editor.renameProviderAuthorities(cloneSuffix)

        editor.setApplicationLabel(options.newLabel)
        val launcherLabels = editor.setLauncherLabels(options.newLabel)
        val removedPermissions = editor.removePermissions(options.permissionsToRemove)
        var allowBackupDisabled = false
        if (options.disableBackup) {
            editor.setApplicationBoolean("allowBackup", AndroidAttr.ALLOW_BACKUP, false)
            allowBackupDisabled = true
        }
        val patchedManifest = editor.toBytes()
        // Re-parse what we are about to ship: a manifest that does not survive
        // encode/decode unchanged is rejected before it reaches the APK.
        val reparsed = ManifestEditor.parse(patchedManifest)
        if (reparsed.packageName() != options.newPackageName) {
            throw CloneException("Patched manifest lost its package name during re-encode")
        }
        val remappedSources = remappedPermissions.map { it.first }.toSet()
        val keptPermissions = reparsed.declaredPermissions()
            .map { it.name }
            .filter { it !in remappedSources }

        onProgress(Progress(Step.REPACKAGE, 25, "Rewriting APK entries"))
        val repackageResult = repackager.repackage(
            source = options.sourceApk,
            output = staging,
            overrides = mapOf(ApkInspector.MANIFEST_NAME to patchedManifest),
            progress = { done, total ->
                if (total > 0) {
                    onProgress(
                        Progress(Step.REPACKAGE, 25 + (done * 45 / total), "Copying entry ${done + 1}/$total")
                    )
                }
            },
        )

        onProgress(Progress(Step.SIGN, 75, "Signing with APK Signature Scheme v2"))
        val signedStaging = File(staging.absolutePath + ".signed")
        try {
            ApkSignatureSchemeV2.sign(staging, signedStaging, signingKey)
            staging.delete()
            if (!signedStaging.renameTo(staging)) {
                throw CloneException("Could not move the signed APK into place")
            }
        } catch (t: Throwable) {
            runCatching { signedStaging.delete() }
            if (t is CloneException) throw t
            throw CloneException("Signing failed: ${t.message}", t)
        }

        onProgress(Progress(Step.VERIFY, 88, "Verifying the rebuilt APK"))
        val signature = ApkSignatureVerifier.verify(staging)
        if (!signature.signatureValid) {
            throw CloneException("Output APK failed signature verification: ${signature.errors.joinToString("; ")}")
        }
        val integrity = ApkIntegrity.verify(staging)
        if (!integrity.isIntact) {
            throw CloneException(
                "Output APK is corrupt: ${integrity.mismatches.take(3).joinToString("; ")}" +
                    if (integrity.mismatches.size > 3) " (+${integrity.mismatches.size - 3} more)" else ""
            )
        }
        val outputMetadata = try {
            inspector.inspect(staging)
        } catch (t: Throwable) {
            throw CloneException("Output manifest cannot be read back: ${t.message}", t)
        }
        if (outputMetadata.packageName != options.newPackageName) {
            throw CloneException(
                "Verification mismatch: output declares ${outputMetadata.packageName}, " +
                    "expected ${options.newPackageName}"
            )
        }
        if (outputMetadata.label != options.newLabel) {
            throw CloneException(
                "Verification mismatch: output label '${outputMetadata.label}', expected '${options.newLabel}'"
            )
        }
        // No original authority may survive the rename: a stale one collides
        // with the original app at install time.
        val renamedOriginals = renamedAuthorities.map { it.first }.toSet()
        val staleAuthorities = outputMetadata.providerAuthorities.filter { it in renamedOriginals }
        if (staleAuthorities.isNotEmpty()) {
            throw CloneException("Verification mismatch: authorities were not renamed: $staleAuthorities")
        }
        // Remapped permission pairs must both be gone/present consistently.
        val survivingOldPermissions = reparsed.declaredPermissions().map { it.name }.filter { it in remappedSources }
        if (survivingOldPermissions.isNotEmpty()) {
            throw CloneException(
                "Verification mismatch: clone-owned permissions were not remapped: $survivingOldPermissions"
            )
        }

        onProgress(Progress(Step.PUBLISH, 95, "Publishing the verified APK"))
        publishAtomically(staging, options.outputApk)

        onProgress(Progress(Step.DONE, 100, "Clone ready"))
        val warnings = mutableListOf<String>()
        warnings += repackageResult.warnings
        warnings += findings.filter { it.severity == Severity.WARNING }.map { "${it.title}: ${it.detail}" }
        if (renamedAuthorities.isNotEmpty()) {
            warnings.add(
                "Renamed provider authorities (${renamedAuthorities.map { it.second }.joinToString(", ")}). " +
                    "Authority strings embedded in the app's DEX code or resources were NOT rewritten; changing " +
                    "the manifest alone cannot guarantee working provider access, so features that look up the " +
                    "original authority may fail in the clone."
            )
        }
        if (keptPermissions.isNotEmpty()) {
            warnings.add(
                "Custom permission declaration(s) kept as-is: ${keptPermissions.joinToString(", ")}. Android " +
                    "permission names are global; declarations not owned by the clone's package stay shared " +
                    "with their real owner."
            )
        }
        if (normalizedNames.isNotEmpty()) {
            warnings.add(
                "Normalized ${normalizedNames.size} relative manifest class name(s) against the original " +
                    "package so they keep resolving into the unmodified DEX."
            )
        }

        return Report(
            outputApk = options.outputApk,
            changes = CloneChanges(
                sourcePackage = oldPackage,
                newPackage = options.newPackageName,
                label = options.newLabel,
                removedPermissions = removedPermissions,
                renamedAuthorities = renamedAuthorities,
                renamedLauncherLabels = launcherLabels,
                keptPermissions = keptPermissions,
                remappedPermissions = remappedPermissions,
                normalizedClassNames = normalizedNames,
                allowBackupDisabled = allowBackupDisabled,
                repackagedEntryCount = repackageResult.entryCount,
            ),
            sourceMetadata = sourceMetadata,
            outputMetadata = outputMetadata,
            signatureSubject = signature.signerSubject,
            warnings = warnings,
        )
    }

    /**
     * Moves the verified staging file over [destination] in one step. Uses
     * `renameTo` (atomic within a filesystem); on the rare failure it retries
     * through a second temp file placed next to the destination. Until this
     * returns, [destination] keeps any previous clone byte-for-byte intact.
     */
    private fun publishAtomically(staging: File, destination: File) {
        if (staging.renameTo(destination)) return
        val side = File(destination.parentFile, destination.name + ".publish-" + uniqueToken())
        try {
            staging.copyTo(side, overwrite = true)
            if (side.renameTo(destination)) return
            if (!destination.exists()) {
                side.copyTo(destination, overwrite = true)
                return
            }
            throw CloneException("Cannot replace ${destination.absolutePath} with the newly built clone")
        } finally {
            runCatching { side.delete() }
        }
    }

    /** Recovers the clone index N from a `com.example.app.cloneN` style name. */
    private fun cloneIndexOf(newPackage: String, oldPackage: String): Int {
        if (newPackage.startsWith("$oldPackage.clone")) {
            newPackage.substring(oldPackage.length + ".clone".length).toIntOrNull()?.let { return it }
        }
        return 1
    }
}

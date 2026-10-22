package com.yundac.apkcloner.core.clone

import com.android.apksig.ApkVerifier
import com.yundac.apkcloner.core.apk.ApkInspector
import com.yundac.apkcloner.core.apk.ApkIntegrity
import com.yundac.apkcloner.core.axml.ManifestEditor
import com.yundac.apkcloner.core.sign.ApkSignatureVerifier
import com.yundac.apkcloner.core.sign.SigningKeyStore
import com.yundac.apkcloner.core.support.TestApkFactory
import com.yundac.apkcloner.core.support.TestManifests
import com.yundac.apkcloner.core.support.ZipProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ClonePipelineTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var signingKeyPath: File

    @Before
    fun setUp() {
        signingKeyPath = File(temporary.root, "signing/clone.p12")
    }

    @Test
    fun `clones a plain APK and verifies every result`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val sourceHash = sha256(source)

        val output = File(temporary.root, "output/com.example.clone1.apk")
        val report = pipeline().clone(
            CloneOptions(
                sourceApk = source,
                outputApk = output,
                workingDirectory = File(temporary.root, "work"),
                newPackageName = "com.example.clone1",
                newLabel = "Source App Clone",
                permissionsToRemove = setOf("android.permission.CAMERA"),
            ),
            deviceSdkInt = 34,
        )

        // 1. The source APK must not be modified.
        assertEquals(sourceHash, sha256(source))

        // 2. Reported changes match the request.
        assertEquals("com.example.source", report.changes.sourcePackage)
        assertEquals("com.example.clone1", report.changes.newPackage)
        assertEquals(listOf("android.permission.CAMERA"), report.changes.removedPermissions)
        assertEquals("Source App Clone", report.changes.label)
        assertTrue(report.changes.renamedAuthorities.any { it.second == "com.example.source.fileprovider.clone1" })

        // 3. The output is a valid, signed, intact APK.
        assertTrue(output.isFile)
        val signature = ApkSignatureVerifier.verify(output)
        assertTrue("signature errors: ${signature.errors}", signature.signatureValid)
        assertNotNull(signature.signerSubject)

        val apksig = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("apksig errors: ${apksig.errors}", apksig.isVerified)

        assertTrue(ApkIntegrity.verify(output).isIntact)

        // 4. Manifest changes are really in the written APK.
        val metadata = ApkInspector().inspect(output)
        assertEquals("com.example.clone1", metadata.packageName)
        assertEquals("Source App Clone", metadata.label)
        assertFalse(metadata.permissions.contains("android.permission.CAMERA"))
        assertTrue(metadata.permissions.contains("android.permission.INTERNET"))
        assertEquals(listOf("com.example.source.fileprovider.clone1"), metadata.providerAuthorities)

        // 5. Class names still point at the original DEX classes.
        val editor = ManifestEditor.parse(ApkInspector().readManifestBytes(output))
        val launcher = editor.applicationElement()!!
            .children
            .filter { it.name == "activity" }
            .first { editor.hasLauncherIntent(it) }
        assertEquals(
            "com.example.source.MainActivity",
            editor.document.stringValue(launcher.frameworkAttribute("name")),
        )

        // 6. Native libraries stay page aligned in the output.
        val library = ZipProbe(output).find("lib/arm64-v8a/libnative.so")
        assertEquals(0L, library!!.dataOffset % 16384)

        // 7. The recorded output metadata is consistent with a fresh read.
        assertEquals(metadata.packageName, report.outputMetadata.packageName)
    }

    @Test
    fun `applies the optional backup override`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard(allowBackup = true))
        val output = File(temporary.root, "output/backup.apk")

        val report = pipeline().clone(
            CloneOptions(
                sourceApk = source,
                outputApk = output,
                workingDirectory = File(temporary.root, "work"),
                newPackageName = "com.example.clone2",
                newLabel = "No Backup",
                disableBackup = true,
            ),
            deviceSdkInt = 34,
        )

        assertTrue(report.changes.allowBackupDisabled)
        val editor = ManifestEditor.parse(ApkInspector().readManifestBytes(output))
        assertEquals(0, editor.applicationElement()!!.frameworkAttribute("allowBackup")!!.data)
    }

    @Test
    fun `rejects an invalid target package name`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())

        val thrown = runCatching {
            pipeline().clone(
                CloneOptions(
                    sourceApk = source,
                    outputApk = File(temporary.root, "output/bad.apk"),
                    workingDirectory = File(temporary.root, "work"),
                    newPackageName = "notvalid",
                    newLabel = "Bad",
                ),
                deviceSdkInt = 34,
            )
        }.exceptionOrNull()

        assertTrue(thrown is ClonePipeline.CloneException)
        assertTrue(thrown!!.message!!.contains("Invalid target package name"))
    }

    @Test
    fun `rejects a split APK before touching the output`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard(split = "config.arm64_v8a"))
        val output = File(temporary.root, "output/split.apk")

        val thrown = runCatching {
            pipeline().clone(
                CloneOptions(
                    sourceApk = source,
                    outputApk = output,
                    workingDirectory = File(temporary.root, "work"),
                    newPackageName = "com.example.clone3",
                    newLabel = "Split",
                ),
                deviceSdkInt = 34,
            )
        }.exceptionOrNull()

        assertTrue(thrown is ClonePipeline.CloneException)
        assertTrue(thrown!!.message!!.contains("cannot be cloned"))
        assertFalse(output.exists())
    }

    @Test
    fun `rejects an app installed from split APKs`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())

        val thrown = runCatching {
            pipeline().clone(
                CloneOptions(
                    sourceApk = source,
                    outputApk = File(temporary.root, "output/split.apk"),
                    workingDirectory = File(temporary.root, "work"),
                    newPackageName = "com.example.clone5",
                    newLabel = "Split",
                    installedSplits = listOf("base.apk", "split_config.arm64_v8a.apk"),
                ),
                deviceSdkInt = 34,
            )
        }.exceptionOrNull()

        assertTrue(thrown is ClonePipeline.CloneException)
        assertTrue(thrown!!.message!!.contains("split APKs"))
    }

    @Test
    fun `refuses to overwrite the source APK`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())

        val thrown = runCatching {
            pipeline().clone(
                CloneOptions(
                    sourceApk = source,
                    outputApk = source,
                    workingDirectory = File(temporary.root, "work"),
                    newPackageName = "com.example.clone4",
                    newLabel = "Same",
                ),
                deviceSdkInt = 34,
            )
        }.exceptionOrNull()
        assertTrue(thrown is ClonePipeline.CloneException)
    }

    private fun pipeline(): ClonePipeline {
        val key = SigningKeyStore.loadOrCreate(signingKeyPath, "pipeline-password".toCharArray())
        return ClonePipeline(key)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

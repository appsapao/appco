package com.yundac.apkcloner.core.clone

import com.android.apksig.ApkVerifier
import com.yundac.apkcloner.core.apk.ApkInspector
import com.yundac.apkcloner.core.apk.ApkIntegrity
import com.yundac.apkcloner.core.sign.ApkSignatureVerifier
import com.yundac.apkcloner.core.sign.SigningKeyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional end-to-end check against a real, production APK.
 *
 * Synthetic fixtures are small and controlled; this test exists to prove the
 * pipeline against a real manifest produced by the Android toolchain -- 2788
 * resources, ~10k classes, real provider authorities and launcher components.
 *
 * Enable it by pointing `APKCLONER_SAMPLE_APK` at any installed app's base APK.
 * It is skipped when the variable is unset, so CI stays hermetic.
 */
class RealApkSmokeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `clones a real APK and AOSP tooling reads the result`() {
        val sample = System.getenv("APKCLONER_SAMPLE_APK")?.let(::File)
        assumeTrue("APKCLONER_SAMPLE_APK is not set; skipping real-APK smoke test", sample?.isFile == true)

        val inspector = ApkInspector()
        val source = inspector.inspect(sample!!)
        println(
            "SMOKE source package=${source.packageName} version=${source.versionName} " +
                "targetSdk=${source.targetSdk} entries=${source.entryCount} " +
                "sizeMiB=${source.apkSizeBytes / (1024 * 1024)} permissions=${source.permissions.size}"
        )
        assertTrue("sample should be a real app", source.entryCount > 100)
        assertFalse("sample must not be a split APK", source.isSplitApk)

        val output = File(temporary.root, "clone.apk")
        val key = SigningKeyStore.loadOrCreate(
            File(temporary.root, "signing.p12"),
            "smoke-test-password".toCharArray(),
        )
        val started = System.nanoTime()
        val report = ClonePipeline(key).clone(
            CloneOptions(
                sourceApk = sample,
                outputApk = output,
                workingDirectory = File(temporary.root, "work"),
                newPackageName = "com.yundac.smoke.clone1",
                newLabel = "Clone Smoke",
            ),
            deviceSdkInt = 34,
        )
        val seconds = (System.nanoTime() - started) / 1_000_000_000
        println("SMOKE cloned in ${seconds}s -> ${output.length()} bytes")

        assertEquals("com.yundac.smoke.clone1", report.outputMetadata.packageName)
        assertEquals("Clone Smoke", report.outputMetadata.label)
        assertTrue(ApkSignatureVerifier.verify(output).signatureValid)
        assertTrue(ApkIntegrity.verify(output).isIntact)

        val apksig = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("apksig errors: ${apksig.errors}", apksig.isVerified)

        // The manifest is re-encoded binary XML. If AOSP's own parser reads the
        // result, the platform parser will too.
        val aapt2 = findAapt2()
        if (aapt2 == null) {
            println("SMOKE aapt2 not available; skipped binary-XML cross-check")
            return
        }
        val badging = runCommand(aapt2.absolutePath, "dump", "badging", output.absolutePath)
        assertTrue("aapt2 rejected the cloned APK:\n$badging", badging.contains("package: name='com.yundac.smoke.clone1'"))
        assertTrue("aapt2 did not see the new label", badging.contains("application-label:'Clone Smoke'"))

        val xmlTree = runCommand(aapt2.absolutePath, "dump", "xmltree", output.absolutePath, "--file", "AndroidManifest.xml")
        assertTrue(xmlTree.contains("E: manifest"))
        assertTrue(xmlTree.contains("com.yundac.smoke.clone1"))
        // Class names must survive the package rename.
        assertTrue(
            "original activity class name should be preserved",
            xmlTree.contains("com.applisto.appcloner.activity.StartActivity"),
        )
    }

    private fun findAapt2(): File? {
        val candidates = mutableListOf<File>()
        val sdkRoots = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
        ).map(::File)
        sdkRoots.forEach { root ->
            val buildTools = File(root, "build-tools")
            buildTools.listFiles()?.sortedByDescending { it.name }?.forEach { version ->
                candidates.add(File(version, "aapt2"))
                candidates.add(File(version, "aapt2.exe"))
            }
        }
        System.getenv("PATH")?.split(File.pathSeparator)?.forEach { dir ->
            candidates.add(File(dir, "aapt2"))
            candidates.add(File(dir, "aapt2.exe"))
        }
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Command timed out: ${command.joinToString(" ")}")
        }
        return output
    }
}

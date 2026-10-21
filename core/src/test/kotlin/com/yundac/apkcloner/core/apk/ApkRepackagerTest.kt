package com.yundac.apkcloner.core.apk

import com.yundac.apkcloner.core.support.TestApkFactory
import com.yundac.apkcloner.core.support.TestManifests
import com.yundac.apkcloner.core.support.ZipProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry

class ApkRepackagerTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `drops old signatures and applies overrides`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val replacement = TestManifests.standard(packageName = "com.example.replaced")

        val output = File(temporary.root, "out.apk")
        val result = ApkRepackager().repackage(
            source = source,
            output = output,
            overrides = mapOf("AndroidManifest.xml" to replacement),
        )

        val names = ZipProbe(output).names()
        assertFalse("META-INF/MANIFEST.MF must be dropped", names.contains("META-INF/MANIFEST.MF"))
        assertFalse("v1 signature must be dropped", names.contains("META-INF/CERT.RSA"))
        assertFalse(names.contains("META-INF/CERT.SF"))
        assertTrue(names.contains("classes.dex"))
        assertTrue(names.contains("AndroidManifest.xml"))
        assertEquals(result.entryCount, ZipProbe(output).entries.size)

        val manifestBytes = ZipFileReader.read(output, "AndroidManifest.xml")
        assertEquals("com.example.replaced", ManifestEditorHolder.packageOf(manifestBytes))
    }

    @Test
    fun `keeps resources arsc stored and 4 byte aligned`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val output = File(temporary.root, "out.apk")
        ApkRepackager().repackage(source, output)

        val entry = ZipProbe(output).find("resources.arsc")
        assertNotNull(entry)
        assertEquals("resources.arsc must be stored", ZipEntry.STORED, entry!!.method)
        assertEquals("resources.arsc must be 4-byte aligned", 0L, entry.dataOffset % 4)
    }

    @Test
    fun `keeps native libraries stored and page aligned`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val output = File(temporary.root, "out.apk")
        ApkRepackager().repackage(source, output)

        val library = ZipProbe(output).find("lib/arm64-v8a/libnative.so")
        assertNotNull(library)
        assertEquals(ZipEntry.STORED, library!!.method)
        assertEquals("native library must be 16 KiB aligned", 0L, library.dataOffset % 16384)
    }

    @Test
    fun `entry payloads survive the round trip`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(
            source,
            TestManifests.standard(),
            extraEntries = mapOf("assets/empty.txt" to ByteArray(0)),
        )
        val output = File(temporary.root, "out.apk")
        ApkRepackager().repackage(source, output)

        assertEquals("clone fixture payload", String(ZipFileReader.read(output, "res/raw/payload.txt")))
        assertEquals(0, ZipFileReader.read(output, "assets/empty.txt").size)
        assertEquals(4096, ZipFileReader.read(output, "lib/arm64-v8a/libnative.so").size)
        assertEquals(2048, ZipFileReader.read(output, "resources.arsc").size)

        val integrity = ApkIntegrity.verify(output)
        assertTrue("integrity: ${integrity.mismatches}", integrity.isIntact)
        assertEquals(ZipProbe(source).entries.size - 3, integrity.entryCount)
    }

    @Test
    fun `warns about overrides for missing entries`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val output = File(temporary.root, "out.apk")

        val result = ApkRepackager().repackage(
            source,
            output,
            overrides = mapOf("does/not/exist.txt" to ByteArray(4)),
        )
        assertTrue(result.warnings.any { it.contains("does/not/exist.txt") })
    }

    @Test
    fun `output is a readable ZIP with a valid EOCD`() {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())
        val output = File(temporary.root, "out.apk")
        ApkRepackager().repackage(source, output)

        java.util.zip.ZipFile(output).use { zip ->
            assertNotNull(zip.getEntry("AndroidManifest.xml"))
            assertNull(zip.getEntry("META-INF/CERT.RSA"))
        }
        assertTrue(output.length() > 0)
    }

    private object ZipFileReader {
        fun read(file: File, name: String): ByteArray =
            java.util.zip.ZipFile(file).use { zip ->
                val entry = zip.getEntry(name) ?: error("Missing entry $name")
                zip.getInputStream(entry).readBytes()
            }
    }

    private object ManifestEditorHolder {
        fun packageOf(manifestBytes: ByteArray): String? =
            com.yundac.apkcloner.core.axml.ManifestEditor.parse(manifestBytes).packageName()
    }
}

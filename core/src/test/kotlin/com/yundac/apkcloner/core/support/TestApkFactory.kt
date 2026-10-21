package com.yundac.apkcloner.core.support

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds small but structurally realistic APK files for tests. */
object TestApkFactory {

    fun build(
        target: File,
        manifest: ByteArray,
        includeResourcesArsc: Boolean = true,
        includeNativeLibrary: Boolean = true,
        includeV1Signature: Boolean = true,
        extraEntries: Map<String, ByteArray> = emptyMap(),
    ): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { fileStream ->
            ZipOutputStream(fileStream.buffered()).use { zip ->
                put(zip, "AndroidManifest.xml", manifest, ZipEntry.DEFLATED)
                put(zip, "classes.dex", dexFile(4096), ZipEntry.DEFLATED)
                if (includeResourcesArsc) {
                    put(zip, "resources.arsc", resourceTable(), ZipEntry.DEFLATED)
                }
                if (includeNativeLibrary) {
                    put(zip, "lib/arm64-v8a/libnative.so", nativeLibrary(), ZipEntry.STORED)
                }
                put(zip, "res/raw/payload.txt", "clone fixture payload".toByteArray(), ZipEntry.DEFLATED)
                extraEntries.forEach { (name, bytes) -> put(zip, name, bytes, ZipEntry.DEFLATED) }
                if (includeV1Signature) {
                    put(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".toByteArray(), ZipEntry.DEFLATED)
                    put(zip, "META-INF/CERT.SF", "Signature-Version: 1.0\r\n\r\n".toByteArray(), ZipEntry.DEFLATED)
                    put(zip, "META-INF/CERT.RSA", ByteArray(64) { 0x42 }, ZipEntry.DEFLATED)
                }
            }
        }
        return target
    }

    private fun put(zip: ZipOutputStream, name: String, data: ByteArray, method: Int) {
        val entry = ZipEntry(name)
        entry.method = method
        if (method == ZipEntry.STORED) {
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            entry.crc = CRC32().apply { update(data) }.value
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }

    private fun dexFile(size: Int): ByteArray {
        val header = "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1)
        val bytes = ByteArrayOutputStream(size)
        bytes.write(header)
        var value = 0
        while (bytes.size() < size) {
            bytes.write(value++ and 0xFF)
        }
        return bytes.toByteArray()
    }

    private fun resourceTable(): ByteArray {
        // Not a complete ARSC table: the pipeline never parses resources, it only
        // has to carry the entry across and keep it stored/aligned.
        val bytes = ByteArray(2048)
        bytes[0] = 0x02
        bytes[1] = 0x00
        bytes[2] = 0x0C
        bytes[3] = 0x00
        for (index in 8 until bytes.size) bytes[index] = (index and 0x7F).toByte()
        return bytes
    }

    private fun nativeLibrary(): ByteArray = ByteArray(4096) { index -> (index * 31 and 0xFF).toByte() }
}

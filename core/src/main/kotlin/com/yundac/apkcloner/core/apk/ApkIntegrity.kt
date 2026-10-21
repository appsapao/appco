package com.yundac.apkcloner.core.apk

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Verifies that every entry of an APK decompresses to the bytes its ZIP central
 * directory claims. `java.util.zip.ZipFile` does *not* check CRCs on read, so a
 * repackaged APK with a truncated entry would otherwise only fail on install.
 */
object ApkIntegrity {

    data class Result(val entryCount: Int, val mismatches: List<String>) {
        val isIntact: Boolean get() = mismatches.isEmpty()
    }

    fun verify(apk: File): Result {
        val mismatches = mutableListOf<String>()
        var count = 0
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                count++
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        crc.update(buffer, 0, read)
                        size += read
                    }
                }
                if (entry.size >= 0 && size != entry.size) {
                    mismatches.add("${entry.name}: size $size != ${entry.size}")
                } else if (entry.crc >= 0 && crc.value != entry.crc) {
                    mismatches.add("${entry.name}: CRC mismatch")
                }
            }
        }
        return Result(count, mismatches)
    }
}

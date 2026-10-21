package com.yundac.apkcloner.core.apk

import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Rewrites an APK as a new ZIP archive with optional entry replacements.
 *
 * The archive is written by hand instead of through `ZipOutputStream` because
 * cloning needs three properties that `java.util.zip` cannot express:
 *
 *  * `resources.arsc` must be STORED and 4-byte aligned (required for
 *    `targetSdk >= 30`), and native libraries must be stored with page
 *    alignment.
 *  * Existing `META-INF` signatures and any previous APK Signing Block must be
 *    dropped, so the clone can be re-signed.
 *  * Entry data offsets must be known exactly, which is what makes alignment
 *    verifiable rather than hopeful.
 */
class ApkRepackager {

    data class Result(
        val entryCount: Int,
        val warnings: List<String>,
    )

    class RepackageException(message: String) : Exception(message)

    fun repackage(
        source: File,
        output: File,
        overrides: Map<String, ByteArray> = emptyMap(),
        progress: ((Int, Int) -> Unit)? = null,
    ): Result {
        output.parentFile?.mkdirs()
        if (output.exists() && !output.delete()) {
            throw RepackageException("Cannot overwrite ${output.absolutePath}")
        }

        val warnings = mutableListOf<String>()
        val entries = mutableListOf<ZipEntry>()
        val seen = HashSet<String>()

        ZipFile(source).use { zip ->
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (entry.isDirectory) continue
                if (isSignatureEntry(entry.name)) continue
                if (!seen.add(entry.name)) {
                    warnings.add("Duplicate entry '${entry.name}' kept once")
                    continue
                }
                if (entry.size > MAX_ENTRY_BYTES) {
                    throw RepackageException(
                        "Entry '${entry.name}' is ${entry.size} bytes; single entries larger than " +
                            "${MAX_ENTRY_BYTES / (1024 * 1024)} MiB are not supported"
                    )
                }
                entries.add(entry)
            }

            if (entries.size > MAX_ENTRIES) {
                throw RepackageException("APK has ${entries.size} entries; ZIP64 APKs are not supported")
            }

            val central = ArrayList<CentralEntry>(entries.size)

            FileOutputStream(output).use { fileStream ->
                // `use` closes the buffered stream, which is what actually
                // persists the last block of data.
                CountingOutputStream(fileStream.buffered(1 shl 16)).use { out ->
                    entries.forEachIndexed { index, entry ->
                        progress?.invoke(index, entries.size)
                        val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                        val replacement = overrides[entry.name]
                        val raw = replacement ?: zip.getInputStream(entry).use { it.readBytes() }

                        val method = chooseMethod(entry, replacement != null)
                        val crc = crc32(raw)
                        val payload = if (method == ZipEntry.DEFLATED) deflate(raw) else raw
                        val alignment = alignmentFor(entry.name, method)
                        val extra = paddingFor(alignment, out.count, nameBytes.size)

                        val localOffset = out.count
                        writeLocalHeader(
                            out = out,
                            nameBytes = nameBytes,
                            method = method,
                            crc = crc,
                            compressedSize = payload.size.toLong(),
                            uncompressedSize = raw.size.toLong(),
                            extra = extra,
                        )
                        out.write(payload)

                        central.add(
                            CentralEntry(
                                nameBytes = nameBytes,
                                method = method,
                                crc = crc,
                                compressedSize = payload.size.toLong(),
                                uncompressedSize = raw.size.toLong(),
                                localHeaderOffset = localOffset,
                            )
                        )
                    }

                    progress?.invoke(entries.size, entries.size)
                    val centralDirectoryOffset = out.count
                    central.forEach { out.write(centralHeader(it)) }
                    val centralDirectorySize = out.count - centralDirectoryOffset
                    out.write(endOfCentralDirectory(entries.size, centralDirectorySize, centralDirectoryOffset))
                }
            }
        }

        overrides.keys.forEach { name ->
            if (entries.none { it.name == name }) warnings.add("Override for missing entry '$name' was not applied")
        }

        return Result(entries.size, warnings)
    }

    private fun chooseMethod(entry: ZipEntry, hasReplacement: Boolean): Int {
        // resources.arsc must be uncompressed and aligned once targetSdk >= 30.
        if (entry.name == "resources.arsc") return ZipEntry.STORED
        if (hasReplacement) return ZipEntry.DEFLATED
        return if (entry.method == ZipEntry.STORED) ZipEntry.STORED else ZipEntry.DEFLATED
    }

    private fun alignmentFor(name: String, method: Int): Int {
        if (method != ZipEntry.STORED) return 0
        if (name == "resources.arsc") return 4
        // Native libraries must be page aligned when stored uncompressed, because
        // the loader maps them straight out of the APK (16 KiB pages on Android 15+).
        if (name.startsWith("lib/") && name.endsWith(".so")) return NATIVE_LIB_ALIGNMENT
        return 0
    }

    /**
     * Builds an extra field that makes the entry's data start at an [alignment]
     * boundary. Uses the standard zipalign-style "alignment" extra field
     * (header id 0xD935) so the padding is well-formed TLV rather than raw bytes.
     */
    private fun paddingFor(alignment: Int, offset: Long, nameLength: Int): ByteArray {
        if (alignment <= 1) return EMPTY
        val dataOffsetWithoutPadding = offset + LOCAL_HEADER_SIZE + nameLength
        var padding = ((alignment - (dataOffsetWithoutPadding % alignment)) % alignment).toInt()
        if (padding == 0) return EMPTY
        // An extra field needs at least its 4-byte TLV header.
        while (padding < 4) padding += alignment

        val extra = ByteArray(padding)
        extra[0] = 0x35
        extra[1] = 0xD9.toByte()
        val payloadSize = padding - 4
        extra[2] = (payloadSize and 0xFF).toByte()
        extra[3] = ((payloadSize shr 8) and 0xFF).toByte()
        return extra
    }

    private fun writeLocalHeader(
        out: CountingOutputStream,
        nameBytes: ByteArray,
        method: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
        extra: ByteArray,
    ) {
        val header = ByteBuffer.allocate(LOCAL_HEADER_SIZE + nameBytes.size + extra.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ZipLayout.LOCAL_HEADER_SIGNATURE)
        header.putU16(20) // version needed
        header.putU16(UTF8_FLAG)
        header.putU16(method)
        header.putU16(DOS_TIME)
        header.putU16(DOS_DATE)
        header.putInt(crc.toInt())
        header.putInt(compressedSize.toInt())
        header.putInt(uncompressedSize.toInt())
        header.putU16(nameBytes.size)
        header.putU16(extra.size)
        header.put(nameBytes)
        header.put(extra)
        out.write(header.array())
    }

    private fun centralHeader(entry: CentralEntry): ByteArray {
        val header = ByteBuffer.allocate(CENTRAL_HEADER_SIZE + entry.nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(ZipLayout.CENTRAL_HEADER_SIGNATURE)
        header.putU16(20) // version made by
        header.putU16(20) // version needed
        header.putU16(UTF8_FLAG)
        header.putU16(entry.method)
        header.putU16(DOS_TIME)
        header.putU16(DOS_DATE)
        header.putInt(entry.crc.toInt())
        header.putInt(entry.compressedSize.toInt())
        header.putInt(entry.uncompressedSize.toInt())
        header.putU16(entry.nameBytes.size)
        header.putU16(0) // extra length
        header.putU16(0) // comment length
        header.putU16(0) // disk number start
        header.putU16(0) // internal attributes
        header.putInt(0) // external attributes
        header.putInt(entry.localHeaderOffset.toInt())
        header.put(entry.nameBytes)
        return header.array()
    }

    private fun ByteBuffer.putU16(value: Int): ByteBuffer = putShort(value.toShort())

    private fun endOfCentralDirectory(entryCount: Int, cdSize: Long, cdOffset: Long): ByteArray {
        val eocd = ByteBuffer.allocate(ZipLayout.EOCD_MIN_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(ZipLayout.EOCD_SIGNATURE)
        eocd.putU16(0) // disk number
        eocd.putU16(0) // disk with central directory
        eocd.putU16(entryCount)
        eocd.putU16(entryCount)
        eocd.putInt(cdSize.toInt())
        eocd.putInt(cdOffset.toInt())
        eocd.putU16(0) // comment length
        return eocd.array()
    }

    private data class CentralEntry(
        val nameBytes: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun write(b: ByteArray) {
            out.write(b)
            count += b.size
        }
    }

    companion object {
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val UTF8_FLAG = 0x0800
        private const val DOS_TIME = 0
        private const val DOS_DATE = 0x0021 // 1980-01-01, fixed for reproducible output
        private const val NATIVE_LIB_ALIGNMENT = 16384
        private const val MAX_ENTRY_BYTES = 96L * 1024 * 1024
        private const val MAX_ENTRIES = 0xFFFE
        private val EMPTY = ByteArray(0)

        private val V1_SIGNATURE_SUFFIXES = listOf(".RSA", ".DSA", ".EC", ".SF")
        private const val V1_MANIFEST = "META-INF/MANIFEST.MF"

        fun isSignatureEntry(name: String): Boolean {
            if (name == V1_MANIFEST) return true
            if (!name.startsWith("META-INF/")) return false
            return V1_SIGNATURE_SUFFIXES.any { name.endsWith(it, ignoreCase = true) }
        }

        fun crc32(data: ByteArray): Long {
            val crc = CRC32()
            crc.update(data)
            return crc.value
        }

        fun deflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = java.io.ByteArrayOutputStream(maxOf(64, data.size / 2))
                val buffer = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    val read = deflater.deflate(buffer)
                    if (read > 0) out.write(buffer, 0, read)
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }
    }
}

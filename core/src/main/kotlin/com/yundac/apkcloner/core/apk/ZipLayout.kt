package com.yundac.apkcloner.core.apk

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Byte offsets of the three structures a v2 signature has to reason about. */
data class ZipSections(
    val centralDirectoryOffset: Int,
    val centralDirectorySize: Int,
    val eocdOffset: Int,
    val eocdSize: Int,
)

object ZipLayout {
    const val LOCAL_HEADER_SIGNATURE: Int = 0x04034b50
    const val CENTRAL_HEADER_SIGNATURE: Int = 0x02014b50
    const val EOCD_SIGNATURE: Int = 0x06054b50

    const val EOCD_MIN_SIZE: Int = 22
    const val MAX_COMMENT_SIZE: Int = 0xFFFF
    const val APK_SIGNING_BLOCK_MAGIC: String = "APK Sig Block 42"

    /** Locates the End Of Central Directory record in a full buffer. */
    fun findEocd(buffer: ByteBuffer): Int {
        val limit = buffer.capacity()
        val lowest = maxOf(0, limit - EOCD_MIN_SIZE - MAX_COMMENT_SIZE)
        var offset = limit - EOCD_MIN_SIZE
        while (offset >= lowest) {
            if (buffer.getInt(offset) == EOCD_SIGNATURE) {
                val commentLength = buffer.getShort(offset + 20).toInt() and 0xFFFF
                if (offset + EOCD_MIN_SIZE + commentLength == limit) return offset
            }
            offset--
        }
        throw ApkFormatException("No End Of Central Directory record found; not a valid ZIP/APK")
    }

    fun readSections(buffer: ByteBuffer): ZipSections {
        val eocd = findEocd(buffer)
        val cdSize = buffer.getInt(eocd + 12)
        val cdOffset = buffer.getInt(eocd + 16)
        if (cdOffset < 0 || cdSize < 0) {
            throw ApkFormatException("ZIP64 or corrupt APK is not supported (central directory offset=$cdOffset)")
        }
        if (cdOffset + cdSize > eocd) {
            throw ApkFormatException(
                "Central directory overlaps the EOCD record; APK layout is not supported " +
                    "(cd=$cdOffset+$cdSize, eocd=$eocd)"
            )
        }
        return ZipSections(cdOffset, cdSize, eocd, buffer.capacity() - eocd)
    }

    /** True when an APK Signing Block immediately precedes the central directory. */
    fun hasSigningBlock(buffer: ByteBuffer, sections: ZipSections): Boolean {
        val magicStart = sections.centralDirectoryOffset - APK_SIGNING_BLOCK_MAGIC.length
        if (magicStart < 0) return false
        val magic = ByteArray(APK_SIGNING_BLOCK_MAGIC.length)
        buffer.get(magicStart, magic)
        return String(magic, Charsets.US_ASCII) == APK_SIGNING_BLOCK_MAGIC
    }

    /** Reads just the tail of a file, which is all the EOCD lookup needs. */
    fun readTail(file: File): ByteBuffer {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < EOCD_MIN_SIZE) throw ApkFormatException("File is too small to be an APK (${length} bytes)")
            val tailSize = minOf(length, (EOCD_MIN_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(length - tailSize)
            raf.readFully(tail)
            return ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /** Detects whether a file carries an APK Signing Block without loading it. */
    fun fileHasSigningBlock(file: File): Boolean {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < EOCD_MIN_SIZE + 16) return false
            val tailSize = minOf(length, (EOCD_MIN_SIZE + MAX_COMMENT_SIZE).toLong()).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(length - tailSize)
            raf.readFully(tail)
            val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val tailEocd = try {
                findEocd(buffer)
            } catch (e: ApkFormatException) {
                return false
            }
            val cdOffsetInTail = buffer.getInt(tailEocd + 16)
            val cdOffsetInFile = cdOffsetInTail.toLong()
            val magicEndInFile = cdOffsetInFile
            val magicStartInFile = magicEndInFile - 16
            if (magicStartInFile < 0 || magicEndInFile > length) return false
            raf.seek(magicStartInFile)
            val magic = ByteArray(16)
            raf.readFully(magic)
            return String(magic, Charsets.US_ASCII) == APK_SIGNING_BLOCK_MAGIC
        }
    }
}

class ApkFormatException(message: String) : Exception(message)

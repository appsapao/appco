package com.yundac.apkcloner.core.support

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads raw ZIP structure, used to assert alignment and storage method of entries. */
class ZipProbe(file: File) {

    class Entry(
        val name: String,
        val method: Int,
        val dataOffset: Long,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val crc: Long,
    )

    val entries: List<Entry>

    init {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val tailSize = minOf(length, 22L + 0xFFFF).toInt()
            val tail = ByteArray(tailSize)
            raf.seek(length - tailSize)
            raf.readFully(tail)
            val buffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)

            var eocd = -1
            var offset = tailSize - 22
            while (offset >= 0) {
                if (buffer.getInt(offset) == 0x06054b50) {
                    val commentLength = buffer.getShort(offset + 20).toInt() and 0xFFFF
                    if (offset + 22 + commentLength == tailSize) {
                        eocd = offset
                        break
                    }
                }
                offset--
            }
            require(eocd >= 0) { "No EOCD in ${file.name}" }

            val count = buffer.getShort(eocd + 10).toInt() and 0xFFFF
            val centralDirectoryOffset = buffer.getInt(eocd + 16).toLong()

            val central = ByteArray((length - centralDirectoryOffset).toInt())
            raf.seek(centralDirectoryOffset)
            raf.readFully(central)
            val centralBuffer = ByteBuffer.wrap(central).order(ByteOrder.LITTLE_ENDIAN)

            val result = ArrayList<Entry>(count)
            var position = 0
            repeat(count) {
                require(centralBuffer.getInt(position) == 0x02014b50) { "Bad central header" }
                val method = centralBuffer.getShort(position + 10).toInt() and 0xFFFF
                val crc = centralBuffer.getInt(position + 16).toLong() and 0xFFFFFFFFL
                val compressedSize = centralBuffer.getInt(position + 20)
                val uncompressedSize = centralBuffer.getInt(position + 24)
                val nameLength = centralBuffer.getShort(position + 28).toInt() and 0xFFFF
                val extraLength = centralBuffer.getShort(position + 30).toInt() and 0xFFFF
                val commentLength = centralBuffer.getShort(position + 32).toInt() and 0xFFFF
                val localOffset = centralBuffer.getInt(position + 42).toLong() and 0xFFFFFFFFL
                val name = String(central, position + 46, nameLength, Charsets.UTF_8)

                val localHeader = ByteArray(30)
                raf.seek(localOffset)
                raf.readFully(localHeader)
                val localBuffer = ByteBuffer.wrap(localHeader).order(ByteOrder.LITTLE_ENDIAN)
                require(localBuffer.getInt(0) == 0x04034b50) { "Bad local header for $name" }
                val localNameLength = localBuffer.getShort(26).toInt() and 0xFFFF
                val localExtraLength = localBuffer.getShort(28).toInt() and 0xFFFF
                val dataOffset = localOffset + 30 + localNameLength + localExtraLength

                result.add(
                    Entry(
                        name = name,
                        method = method,
                        dataOffset = dataOffset,
                        compressedSize = compressedSize,
                        uncompressedSize = uncompressedSize,
                        crc = crc,
                    )
                )
                position += 46 + nameLength + extraLength + commentLength
            }
            entries = result
        }
    }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }

    fun names(): List<String> = entries.map { it.name }
}

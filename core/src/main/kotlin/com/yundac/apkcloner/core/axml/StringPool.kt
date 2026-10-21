package com.yundac.apkcloner.core.axml

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mutable Android string pool.
 *
 * Indexes of existing strings never move: [append] only adds at the end. That
 * property is what makes it safe to add a string to an already-serialized
 * document, because every string reference in the XML chunks stays valid.
 */
class StringPool internal constructor(
    val isUtf8: Boolean,
    initial: List<String>,
) {
    private val values = ArrayList<String>(initial)

    val size: Int get() = values.size

    fun get(index: Int): String = values[index.also {
        require(it in values.indices) { "String pool index $it out of range (size ${values.size})" }
    }]

    fun getOrNull(index: Int): String? = values.getOrNull(index)

    fun indexOf(value: String): Int = values.indexOf(value)

    fun indexOfOrAppend(value: String): Int {
        val existing = values.indexOf(value)
        return if (existing >= 0) existing else append(value)
    }

    fun append(value: String): Int {
        values.add(value)
        return values.size - 1
    }

    fun replace(index: Int, value: String) {
        require(index in values.indices) { "String pool index $index out of range (size ${values.size})" }
        values[index] = value
    }

    fun snapshot(): List<String> = ArrayList(values)

    companion object {
        fun empty(isUtf8: Boolean = true): StringPool = StringPool(isUtf8, listOf(""))
    }
}

/** Reads and writes the `ResStringPool` chunk. */
internal object StringPoolCodec {

    fun chunkSize(buf: ByteBuffer, chunkOffset: Int): Int {
        require(buf.le16(chunkOffset) == ChunkType.STRING_POOL) {
            "Expected a string pool chunk at offset $chunkOffset"
        }
        return buf.le32(chunkOffset + 4)
    }

    fun parse(buf: ByteBuffer, chunkOffset: Int): StringPool {
        check(buf.le16(chunkOffset) == ChunkType.STRING_POOL) {
            "Manifest does not start with a string pool"
        }
        val headerSize = buf.le16(chunkOffset + 2)
        val count = buf.le32(chunkOffset + 8)
        val styleCount = buf.le32(chunkOffset + 12)
        val flags = buf.le32(chunkOffset + 16)
        val stringsStart = buf.le32(chunkOffset + 20)

        require(styleCount == 0) {
            "Manifests with styled string pools are not supported (styleCount=$styleCount)"
        }
        require(stringsStart >= headerSize + count * 4) {
            "Corrupt string pool: stringsStart=$stringsStart headerSize=$headerSize count=$count"
        }

        val isUtf8 = flags and PoolFlag.UTF8 != 0
        val offsetsBase = chunkOffset + headerSize
        val dataBase = chunkOffset + stringsStart
        val result = ArrayList<String>(count)
        for (i in 0 until count) {
            val stringOffset = buf.le32(offsetsBase + i * 4)
            result.add(readString(buf, dataBase + stringOffset, isUtf8))
        }
        return StringPool(isUtf8, result)
    }

    /**
     * Serializes the pool. The sorted flag is never written, because [StringPool.append]
     * can add strings in arbitrary order and a stale "sorted" claim would make the
     * framework's binary-search lookup fail.
     */
    fun encode(pool: StringPool): ByteArray {
        val values = pool.snapshot()
        val offsets = IntArray(values.size)
        val data = ByteArrayOutputStream()

        values.forEachIndexed { index, value ->
            offsets[index] = data.size()
            data.write(encodeString(value, pool.isUtf8))
        }
        while (data.size() % 4 != 0) data.write(0)

        val headerSize = 28
        val stringsStart = headerSize + values.size * 4
        val total = stringsStart + data.size()

        val out = littleEndian(total)
        out.putShort(ChunkType.STRING_POOL.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(total)
        out.putInt(values.size)
        out.putInt(0) // styleCount
        out.putInt(if (pool.isUtf8) PoolFlag.UTF8 else 0)
        out.putInt(stringsStart)
        out.putInt(0) // stylesStart
        offsets.forEach { out.putInt(it) }
        out.put(data.toByteArray())
        return out.array()
    }

    private fun readString(buf: ByteBuffer, offset: Int, isUtf8: Boolean): String =
        if (isUtf8) readUtf8(buf, offset) else readUtf16(buf, offset)

    private fun readUtf8(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        var charCount = buf.byteAt(pos++)
        if (charCount and 0x80 != 0) {
            charCount = ((charCount and 0x7F) shl 8) or buf.byteAt(pos++)
        }
        var byteCount = buf.byteAt(pos++)
        if (byteCount and 0x80 != 0) {
            byteCount = ((byteCount and 0x7F) shl 8) or buf.byteAt(pos++)
        }
        require(byteCount >= 0 && pos + byteCount <= buf.capacity()) { "Corrupt UTF-8 string pool entry" }
        val bytes = ByteArray(byteCount)
        buf.get(pos, bytes)
        // charCount is the UTF-16 length; the decoded value is authoritative.
        return String(bytes, Charsets.UTF_8)
    }

    private fun readUtf16(buf: ByteBuffer, offset: Int): String {
        var pos = offset
        var charCount = buf.le16(pos)
        pos += 2
        if (charCount and 0x8000 != 0) {
            charCount = ((charCount and 0x7FFF) shl 16) or buf.le16(pos)
            pos += 2
        }
        require(charCount >= 0 && pos + charCount * 2 <= buf.capacity()) { "Corrupt UTF-16 string pool entry" }
        val chars = CharArray(charCount)
        val view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(pos)
        view.asCharBuffer().get(chars)
        return String(chars)
    }

    private fun encodeString(value: String, isUtf8: Boolean): ByteArray =
        if (isUtf8) encodeUtf8(value) else encodeUtf16(value)

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val charCount = value.length
        val out = ByteArrayOutputStream(bytes.size + 4)
        writeUtf8Length(out, charCount)
        writeUtf8Length(out, bytes.size)
        out.write(bytes)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeUtf8Length(out: ByteArrayOutputStream, length: Int) {
        require(length <= 0x7FFF) { "String too long for a UTF-8 string pool entry: $length" }
        if (length > 0x7F) {
            out.write((length shr 8) or 0x80)
            out.write(length and 0xFF)
        } else {
            out.write(length)
        }
    }

    private fun encodeUtf16(value: String): ByteArray {
        val charCount = value.length
        val out = ByteArrayOutputStream(charCount * 2 + 4)
        if (charCount > 0x7FFF) {
            val high = (charCount shr 16) or 0x8000
            out.write(high and 0xFF)
            out.write((high shr 8) and 0xFF)
            out.write(charCount and 0xFF)
            out.write((charCount shr 8) and 0xFF)
        } else {
            out.write(charCount and 0xFF)
            out.write((charCount shr 8) and 0xFF)
        }
        for (ch in value) {
            out.write(ch.code and 0xFF)
            out.write((ch.code shr 8) and 0xFF)
        }
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }
}

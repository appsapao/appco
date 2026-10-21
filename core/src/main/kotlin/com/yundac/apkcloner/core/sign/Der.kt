package com.yundac.apkcloner.core.sign

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal DER encoder -- just enough for a self-signed X.509 certificate.
 *
 * Android has no public API for building certificates, and pulling in
 * BouncyCastle would add a ~4 MiB dependency for a few hundred bytes of ASN.1,
 * so the encoding is done here and verified by `CertificateFactory` in tests.
 */
internal object Der {

    fun tlv(tag: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 6)
        out.write(tag)
        out.write(length(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun length(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
        length <= 0xFFFF -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (length shr 16).toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        )
    }

    fun sequence(vararg items: ByteArray): ByteArray = tlv(0x30, concat(items))

    fun set(vararg items: ByteArray): ByteArray = tlv(0x31, concat(items))

    fun integer(value: Long): ByteArray = integer(BigInteger.valueOf(value))

    fun integer(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return tlv(0x02, bytes)
    }

    fun boolean(value: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    fun octetString(bytes: ByteArray): ByteArray = tlv(0x04, bytes)

    fun bitString(bytes: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + bytes)

    fun bitString(unusedBits: Int, bytes: ByteArray): ByteArray =
        tlv(0x03, byteArrayOf(unusedBits.toByte()) + bytes)

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun utf8String(value: String): ByteArray = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun printableString(value: String): ByteArray = tlv(0x13, value.toByteArray(Charsets.US_ASCII))

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    /**
     * X.509 `Time` choice: UTCTime only covers 1950-2049. A certificate that is
     * valid past 2049 (the clone key is valid for 30 years) must use
     * GeneralizedTime, otherwise the encoded year silently lands in 19xx.
     */
    fun generalizedTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x18, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun time(date: Date): ByteArray {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
        val year = calendar.get(java.util.Calendar.YEAR)
        return if (year in 1950..2049) utcTime(date) else generalizedTime(date)
    }

    /** Context-specific constructed tag, e.g. `[0]` for the certificate version field. */
    fun explicit(tagNumber: Int, inner: ByteArray): ByteArray = tlv(0xA0 or tagNumber, inner)

    /** Encodes an OID from its dotted form (e.g. `1.2.840.113549.1.1.11`). */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "OID needs at least two components: $dotted" }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        parts.drop(2).forEach { component ->
            var value = component
            val stack = ArrayList<Int>()
            do {
                stack.add((value and 0x7F).toInt())
                value = value shr 7
            } while (value > 0)
            for (i in stack.indices.reversed()) {
                val byte = stack[i] or if (i == 0) 0 else 0x80
                out.write(byte)
            }
        }
        return tlv(0x06, out.toByteArray())
    }

    private fun concat(items: Array<out ByteArray>): ByteArray {
        val size = items.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (item in items) {
            System.arraycopy(item, 0, out, offset, item.size)
            offset += item.size
        }
        return out
    }
}

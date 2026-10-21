package com.yundac.apkcloner.core.sign

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * `CHUNKED_SHA256`, the content digest used by APK Signature Scheme v2/v3.
 *
 * The construction is easy to get subtly wrong, so it lives in one place and is
 * shared by the signer and the verifier:
 *
 *  * every 1 MiB chunk is digested as `SHA-256(0xa5 || uint32(chunkSize) || chunk)`
 *    -- note the chunk length, and that empty regions produce no chunks at all;
 *  * the result is `SHA-256(0x5a || uint32(totalChunkCount) || chunk digest...)`
 *    with the chunk count spanning all input sections, in order.
 *
 * Verified against `apksig` (and therefore against Android's own verifier) in
 * `SigningTest`.
 */
internal class ChunkedDigest {

    private val chunkDigests = ArrayList<ByteArray>()

    fun addFileRegion(file: File, offset: Long, length: Long) {
        if (length <= 0L) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val buffer = ByteArray(CHUNK_SIZE)
            var remaining = length
            while (remaining > 0) {
                val size = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
                raf.readFully(buffer, 0, size)
                chunkDigests.add(chunkDigest(buffer, 0, size))
                remaining -= size
            }
        }
    }

    fun addBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(bytes.size - offset, CHUNK_SIZE)
            chunkDigests.add(chunkDigest(bytes, offset, size))
            offset += size
        }
    }

    fun finish(): ByteArray {
        val md = MessageDigest.getInstance(DIGEST_ALGORITHM)
        val preamble = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        preamble.put(0x5a)
        preamble.putInt(chunkDigests.size)
        md.update(preamble.array())
        chunkDigests.forEach { md.update(it) }
        return md.digest()
    }

    private fun chunkDigest(data: ByteArray, offset: Int, size: Int): ByteArray {
        val md = MessageDigest.getInstance(DIGEST_ALGORITHM)
        val prefix = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        prefix.put(0xa5.toByte())
        prefix.putInt(size)
        md.update(prefix.array())
        md.update(data, offset, size)
        return md.digest()
    }

    companion object {
        const val CHUNK_SIZE: Int = 1 shl 20
        private const val DIGEST_ALGORITHM = "SHA-256"
    }
}

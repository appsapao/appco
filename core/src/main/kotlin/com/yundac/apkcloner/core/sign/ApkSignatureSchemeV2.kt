package com.yundac.apkcloner.core.sign

import com.yundac.apkcloner.core.apk.ZipLayout
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class SigningException(message: String) : Exception(message)

/** A private key and its certificate, used to sign cloned APKs. */
class ApkSigningKey(val privateKey: java.security.PrivateKey, val certificate: java.security.cert.X509Certificate) {
    val publicKey: java.security.PublicKey get() = certificate.publicKey
}

/**
 * APK Signature Scheme v2 signer (Android 7.0+).
 *
 * v2 is the lowest-cost scheme that is actually sufficient: `targetSdk >= 30`
 * APKs are *rejected* by the platform installer unless they carry a v2 or v3
 * signature, so v1-only signing (the obvious JAR-signing shortcut) produces an
 * APK that cannot be installed on a current device.
 *
 * The signature covers the archive contents in three chunks (entries, central
 * directory, EOCD with a rewritten central-directory offset), which is why the
 * signing block has to be inserted in front of the central directory and the
 * EOCD has to be patched afterwards.
 */
object ApkSignatureSchemeV2 {

    private const val BLOCK_ID_V2 = 0x7109871a
    private const val SIGNATURE_ALGORITHM_RSA_PKCS1_SHA256 = 0x0103
    private const val CHUNK_SIZE = 1 shl 20
    private const val EOCD_CENTRAL_DIRECTORY_OFFSET_FIELD = 16
    private val MAGIC = ZipLayoutMagic.value

    fun sign(input: File, output: File, key: ApkSigningKey) {
        val length = input.length()
        if (length <= 0L) throw SigningException("Input APK is empty")
        if (length > Int.MAX_VALUE) throw SigningException("APKs larger than 2 GiB are not supported")

        val tailSize = minOf(length, (ZipLayout.EOCD_MIN_SIZE + ZipLayout.MAX_COMMENT_SIZE).toLong()).toInt()
        val tail = ByteArray(tailSize)
        RandomAccessFile(input, "r").use { raf ->
            raf.seek(length - tailSize)
            raf.readFully(tail)
        }
        val tailBuffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocdInTail = ZipLayout.findEocd(tailBuffer)
        val eocdOffset = (length - tailSize + eocdInTail).toInt()
        val centralDirectorySize = tailBuffer.getInt(eocdInTail + 12)
        val centralDirectoryOffset = tailBuffer.getInt(eocdInTail + 16)

        if (centralDirectoryOffset < 0 || centralDirectorySize < 0) {
            throw SigningException("ZIP64 archives are not supported")
        }
        if (centralDirectoryOffset.toLong() + centralDirectorySize != eocdOffset.toLong()) {
            throw SigningException(
                "Unexpected ZIP layout: central directory ends at " +
                    "${centralDirectoryOffset + centralDirectorySize} but EOCD starts at $eocdOffset. " +
                    "The APK may already be signed."
            )
        }

        val eocdBytes = tail.copyOfRange(eocdInTail, tailSize)

        // Sections: entries, central directory, and the EOCD. In the digest the
        // EOCD is used with its central-directory offset pointing at where the
        // signing block will start, which is exactly the input's EOCD value.
        val contentDigest = ChunkedDigest().run {
            addFileRegion(input, 0L, centralDirectoryOffset.toLong())
            addFileRegion(input, centralDirectoryOffset.toLong(), centralDirectorySize.toLong())
            addBytes(eocdBytes)
            finish()
        }

        val signedData = buildSignedData(contentDigest, key)
        val signature = java.security.Signature.getInstance("SHA256withRSA").run {
            initSign(key.privateKey)
            update(signedData)
            sign()
        }
        val v2Block = buildV2Block(signedData, signature, key)
        val signingBlock = buildSigningBlock(v2Block)

        val patchedEocd = eocdBytes.copyOf()
        ByteBuffer.wrap(patchedEocd).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(EOCD_CENTRAL_DIRECTORY_OFFSET_FIELD, centralDirectoryOffset + signingBlock.size)

        output.parentFile?.mkdirs()
        FileOutputStream(output).use { out ->
            RandomAccessFile(input, "r").use { raf ->
                copyRange(raf, out, 0L, centralDirectoryOffset.toLong())
                out.write(signingBlock)
                copyRange(
                    raf,
                    out,
                    centralDirectoryOffset.toLong(),
                    (eocdOffset - centralDirectoryOffset).toLong(),
                )
            }
            out.write(patchedEocd)
        }
    }

    private fun buildSignedData(contentDigest: ByteArray, key: ApkSigningKey): ByteArray {
        // Each field is a length-prefixed *sequence* of length-prefixed elements,
        // so exactly one prefix per level.
        val digestRecord = concat(u32(SIGNATURE_ALGORITHM_RSA_PKCS1_SHA256), lengthPrefixed(contentDigest))
        val digests = lengthPrefixed(digestRecord)
        val certificates = lengthPrefixed(key.certificate.encoded)
        val additionalAttributes = ByteArray(0)
        return concat(
            lengthPrefixed(digests),
            lengthPrefixed(certificates),
            lengthPrefixed(additionalAttributes),
        )
    }

    private fun buildV2Block(signedData: ByteArray, signature: ByteArray, key: ApkSigningKey): ByteArray {
        val signatureRecord = concat(u32(SIGNATURE_ALGORITHM_RSA_PKCS1_SHA256), lengthPrefixed(signature))
        val signatures = lengthPrefixed(signatureRecord)
        // v2 signer = signed data, signatures, public key. (The minSdkVersion /
        // maxSdkVersion fields exist only in the v3 signer.)
        val signer = concat(
            lengthPrefixed(signedData),
            lengthPrefixed(signatures),
            lengthPrefixed(key.certificate.publicKey.encoded),
        )
        // The value is a length-prefixed sequence *of* length-prefixed signers:
        // the outer prefix wraps the signer list, not the single signer.
        return lengthPrefixed(lengthPrefixed(signer))
    }

    private fun buildSigningBlock(v2Block: ByteArray): ByteArray {
        val pair = concat(u32(BLOCK_ID_V2), v2Block)
        val pairs = concat(u64(pair.size.toLong()), pair)
        val size = pairs.size + 8 + MAGIC.size
        return concat(u64(size.toLong()), pairs, u64(size.toLong()), MAGIC)
    }

    private fun copyRange(source: RandomAccessFile, target: java.io.OutputStream, offset: Long, length: Long) {
        source.seek(offset)
        val buffer = ByteArray(1 shl 16)
        var remaining = length
        while (remaining > 0) {
            val read = source.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (read < 0) throw SigningException("Unexpected end of input while copying the APK")
            target.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun lengthPrefixed(value: ByteArray): ByteArray = concat(u32(value.size), value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun u32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun u64(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (part in parts) {
            System.arraycopy(part, 0, out, offset, part.size)
            offset += part.size
        }
        return out
    }

    private object ZipLayoutMagic {
        val value: ByteArray = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
    }
}

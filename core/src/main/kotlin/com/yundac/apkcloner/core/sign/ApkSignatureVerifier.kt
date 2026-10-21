package com.yundac.apkcloner.core.sign

import com.yundac.apkcloner.core.apk.ZipLayout
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Independent reader for APK Signature Scheme v2 blocks.
 *
 * The clone pipeline re-reads every APK it produces with this verifier, so a
 * clone is only reported as built after its signature has been recomputed from
 * the file and checked against the signing certificate. Unit tests additionally
 * cross-check the same APKs with Google's `apksig` verifier.
 */
object ApkSignatureVerifier {

    data class Result(
        val hasSigningBlock: Boolean,
        val schemeV2Present: Boolean,
        val signatureValid: Boolean,
        val signerSubject: String?,
        val errors: List<String>,
    ) {
        companion object {
            fun unsigned(errors: List<String> = emptyList()) =
                Result(false, false, false, null, errors)
        }
    }

    private const val BLOCK_ID_V2 = 0x7109871a
    private const val ALG_RSA_PKCS1_SHA256 = 0x0103

    fun verify(file: File): Result {
        return try {
            verifyOrThrow(file)
        } catch (t: Throwable) {
            Result(false, false, false, null, listOf(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun verifyOrThrow(file: File): Result {
        val length = file.length()
        if (length < 32) return Result.unsigned(listOf("File is too small to be a signed APK"))
        val tailSize = minOf(length, (ZipLayout.EOCD_MIN_SIZE + ZipLayout.MAX_COMMENT_SIZE).toLong()).toInt()
        val tail = readRegion(file, length - tailSize, tailSize)
        val tailBuffer = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        val eocdInTail = ZipLayout.findEocd(tailBuffer)
        val eocdOffset = length - tailSize + eocdInTail
        val centralDirectoryOffset = tailBuffer.getInt(eocdInTail + 16)

        if (centralDirectoryOffset < 32) return Result.unsigned(listOf("APK has no signing block"))
        val magicStart = centralDirectoryOffset - 16
        val magic = readRegion(file, magicStart.toLong(), 16)
        if (String(magic, Charsets.US_ASCII) != ZipLayout.APK_SIGNING_BLOCK_MAGIC) {
            return Result.unsigned(listOf("APK has no signing block"))
        }

        val sizeField = readRegion(file, centralDirectoryOffset - 24L, 8)
        val blockSize = ByteBuffer.wrap(sizeField).order(ByteOrder.LITTLE_ENDIAN).long
        val blockStart = centralDirectoryOffset - 8L - blockSize
        if (blockStart < 0 || blockSize < 32) {
            return Result(false, false, false, null, listOf("APK signing block has an invalid size"))
        }
        val block = readRegion(file, blockStart, (blockSize + 8).toInt())

        val v2Block = findV2Block(block)
            ?: return Result(true, false, false, null, listOf("APK signing block has no v2 signature"))

        val signer = parseSigner(v2Block)
        val errors = mutableListOf<String>()

        val certificate = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(signer.certificate.inputStream()) as X509Certificate
        }.getOrElse {
            errors.add("Signer certificate could not be parsed: ${it.message}")
            null
        }

        val digestAlgorithm = signer.digests.firstOrNull { it.algorithm == ALG_RSA_PKCS1_SHA256 }
            ?: return Result(true, true, false, certificate?.subjectDN?.name, listOf("No SHA-256 digest in the v2 signer"))

        val eocd = readRegion(file, eocdOffset, (length - eocdOffset).toInt())
        val patchedEocd = eocd.copyOf()
        // In the digest, the EOCD's central-directory offset points at the signing block.
        ByteBuffer.wrap(patchedEocd).order(ByteOrder.LITTLE_ENDIAN).putInt(16, blockStart.toInt())

        val actualDigest = ChunkedDigest().run {
            addFileRegion(file, 0L, blockStart)
            addFileRegion(file, centralDirectoryOffset.toLong(), eocdOffset - centralDirectoryOffset)
            addBytes(patchedEocd)
            finish()
        }

        if (!actualDigest.contentEquals(digestAlgorithm.digest)) {
            errors.add("Signature digest does not match the APK contents")
        }

        if (certificate != null && errors.isEmpty()) {
            val signature = signer.signatures.firstOrNull { it.algorithm == ALG_RSA_PKCS1_SHA256 }
            if (signature == null) {
                errors.add("No RSA/SHA-256 signature in the v2 signer")
            } else {
                val valid = runCatching {
                    java.security.Signature.getInstance("SHA256withRSA").run {
                        initVerify(certificate.publicKey)
                        update(signer.signedData)
                        verify(signature.bytes)
                    }
                }.getOrElse {
                    errors.add("Signature verification failed: ${it.message}")
                    false
                }
                if (!valid && errors.isEmpty()) errors.add("Signature does not verify against the signer certificate")
            }
        }

        return Result(
            hasSigningBlock = true,
            schemeV2Present = true,
            signatureValid = errors.isEmpty(),
            signerSubject = certificate?.subjectX500Principal?.name,
            errors = errors,
        )
    }

    private class Signer(
        val signedData: ByteArray,
        val digests: List<DigestEntry>,
        val signatures: List<SignatureEntry>,
        val certificate: ByteArray,
    )

    private class DigestEntry(val algorithm: Int, val digest: ByteArray)
    private class SignatureEntry(val algorithm: Int, val bytes: ByteArray)

    private fun findV2Block(block: ByteArray): ByteArray? {
        var position = 8
        val pairsEnd = block.size - 24
        while (position + 12 <= pairsEnd) {
            val pairLength = ByteBuffer.wrap(block, position, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (pairLength < 4 || position + 8 + pairLength > pairsEnd) return null
            val id = ByteBuffer.wrap(block, position + 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == BLOCK_ID_V2) {
                val start = position + 12
                val end = position + 8 + pairLength.toInt()
                return block.copyOfRange(start, end)
            }
            position += 8 + pairLength.toInt()
        }
        return null
    }

    /** Parses the first signer of the v2 block. */
    private fun parseSigner(v2Block: ByteArray): Signer {
        val reader = Reader(v2Block)
        val signersSequence = reader.lengthPrefixed()
        val signerReader = Reader(signersSequence)
        val signerBytes = signerReader.lengthPrefixed()
        val signer = Reader(signerBytes)
        val signedData = signer.lengthPrefixed()
        // v2 signers carry no minSdk/maxSdk fields (those are v3 only).
        val signaturesSequence = signer.lengthPrefixed()
        signer.lengthPrefixed() // public key

        val signedDataReader = Reader(signedData)
        val digestsSequence = signedDataReader.lengthPrefixed()
        val certificatesSequence = signedDataReader.lengthPrefixed()

        val digests = mutableListOf<DigestEntry>()
        val digestsReader = Reader(digestsSequence)
        while (digestsReader.remaining > 0) {
            val record = Reader(digestsReader.lengthPrefixed())
            val algorithm = record.u32()
            digests.add(DigestEntry(algorithm, record.lengthPrefixed()))
        }

        val certificates = mutableListOf<ByteArray>()
        val certificatesReader = Reader(certificatesSequence)
        while (certificatesReader.remaining > 0) {
            certificates.add(certificatesReader.lengthPrefixed())
        }

        val signatures = mutableListOf<SignatureEntry>()
        val signaturesReader = Reader(signaturesSequence)
        while (signaturesReader.remaining > 0) {
            val record = Reader(signaturesReader.lengthPrefixed())
            val algorithm = record.u32()
            signatures.add(SignatureEntry(algorithm, record.lengthPrefixed()))
        }

        return Signer(signedData, digests, signatures, certificates.firstOrNull() ?: ByteArray(0))
    }

    private class Reader(private val data: ByteArray) {
        private var position = 0

        val remaining: Int get() = data.size - position

        fun u32(): Int {
            require(position + 4 <= data.size) { "Truncated signature block" }
            val value = ByteBuffer.wrap(data, position, 4).order(ByteOrder.LITTLE_ENDIAN).int
            position += 4
            return value
        }

        fun lengthPrefixed(): ByteArray {
            val size = u32()
            require(size >= 0 && position + size <= data.size) { "Corrupt length-prefixed field" }
            val slice = data.copyOfRange(position, position + size)
            position += size
            return slice
        }

        fun skip(count: Int) {
            require(position + count <= data.size) { "Truncated signature block" }
            position += count
        }
    }

    private fun readRegion(file: File, offset: Long, length: Int): ByteArray {
        val data = ByteArray(length)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(data)
        }
        return data
    }
}

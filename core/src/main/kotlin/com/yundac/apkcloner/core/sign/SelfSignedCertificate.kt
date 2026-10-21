package com.yundac.apkcloner.core.sign

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Builds a self-signed X.509 v3 certificate for the clone signing key.
 *
 * The certificate is intentionally a *debug-grade* identity: it only exists so
 * Android will accept another signature for the cloned package name. It is not
 * a substitute for the original developer's signing identity.
 */
object SelfSignedCertificate {

    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME_OID = "2.5.4.3"
    private const val ORGANIZATION_OID = "2.5.4.10"
    private const val ORGANIZATIONAL_UNIT_OID = "2.5.4.11"
    private const val COUNTRY_OID = "2.5.4.6"
    private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"
    private const val KEY_USAGE_OID = "2.5.29.15"

    fun create(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        commonName: String,
        notBefore: Date = Date(System.currentTimeMillis() - 24L * 60 * 60 * 1000),
        notAfter: Date = Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000),
    ): X509Certificate {
        val signatureAlgorithm = Der.sequence(Der.oid(SHA256_WITH_RSA_OID), Der.nullValue())

        val name = Der.sequence(
            Der.set(Der.sequence(Der.oid(COMMON_NAME_OID), Der.utf8String(commonName))),
            Der.set(Der.sequence(Der.oid(ORGANIZATION_OID), Der.utf8String("APK Cloner"))),
            Der.set(Der.sequence(Der.oid(ORGANIZATIONAL_UNIT_OID), Der.utf8String("Local build"))),
            Der.set(Der.sequence(Der.oid(COUNTRY_OID), Der.printableString("US"))),
        )

        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)

        val extensions = Der.sequence(
            // BasicConstraints, critical, cA = FALSE (empty SEQUENCE means cA=false)
            Der.sequence(
                Der.oid(BASIC_CONSTRAINTS_OID),
                Der.boolean(true),
                Der.octetString(Der.sequence()),
            ),
            // KeyUsage, critical, digitalSignature only
            Der.sequence(
                Der.oid(KEY_USAGE_OID),
                Der.boolean(true),
                Der.octetString(Der.bitString(unusedBits = 7, bytes = byteArrayOf(0x80.toByte()))),
            ),
        )

        val tbsCertificate = Der.sequence(
            Der.explicit(0, Der.integer(2)), // version v3
            Der.integer(serial),
            signatureAlgorithm,
            name,
            Der.sequence(Der.time(notBefore), Der.time(notAfter)),
            name,
            publicKey.encoded,
            Der.explicit(3, extensions),
        )

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(tbsCertificate)
        val signatureBytes = signature.sign()

        val certificateDer = Der.sequence(
            tbsCertificate,
            signatureAlgorithm,
            Der.bitString(signatureBytes),
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certificateDer)) as X509Certificate
    }
}

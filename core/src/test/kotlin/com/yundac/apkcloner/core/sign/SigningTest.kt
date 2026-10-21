package com.yundac.apkcloner.core.sign

import com.android.apksig.ApkVerifier
import com.yundac.apkcloner.core.support.TestApkFactory
import com.yundac.apkcloner.core.support.TestManifests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory

class SigningTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `self signed certificate parses, is valid and self verifies`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keyPair.private, keyPair.public, "APK Cloner Test")

        certificate.checkValidity()
        certificate.verify(keyPair.public)

        val reparsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.encoded.inputStream()) as java.security.cert.X509Certificate
        assertEquals(certificate.subjectX500Principal, reparsed.subjectX500Principal)
        assertTrue(reparsed.subjectX500Principal.name.contains("APK Cloner Test"))
    }

    @Test
    fun `v2 signature is accepted by this project's verifier and by apksig`() {
        val apk = signedFixture()

        val result = ApkSignatureVerifier.verify(apk)
        assertTrue("local verifier errors: ${result.errors}", result.signatureValid)
        assertTrue(result.schemeV2Present)
        assertNotNull(result.signerSubject)

        val apksig = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
        assertTrue("apksig errors: ${apksig.errors}", apksig.isVerified)
        assertTrue(apksig.isVerifiedUsingV2Scheme)
    }

    @Test
    fun `tampering with entry data breaks the signature`() {
        val apk = signedFixture()
        val probe = com.yundac.apkcloner.core.support.ZipProbe(apk)
        val target = probe.find("res/raw/payload.txt")!!
        RandomAccessFile(apk, "rw").use { raf ->
            raf.seek(target.dataOffset)
            val original = raf.readByte()
            raf.seek(target.dataOffset)
            raf.writeByte(original.toInt() xor 0xFF)
        }

        val local = ApkSignatureVerifier.verify(apk)
        assertFalse("modified APK must not verify locally", local.signatureValid)

        val apksig = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
        assertFalse("modified APK must not verify with apksig", apksig.isVerified)
    }

    @Test
    fun `unsigned APK is reported as unsigned`() {
        val apk = temporary.newFile("unsigned.apk")
        TestApkFactory.build(apk, TestManifests.standard())

        val result = ApkSignatureVerifier.verify(apk)
        assertFalse(result.hasSigningBlock)
        assertFalse(result.signatureValid)
        assertTrue(result.errors.isNotEmpty())
    }

    @Test
    fun `signing keys round trip through the keystore`() {
        val keystore = File(temporary.root, "keys/test.p12")
        val password = "test-password".toCharArray()

        val first = SigningKeyStore.loadOrCreate(keystore, password, commonName = "Round Trip")
        val second = SigningKeyStore.loadOrCreate(keystore, password)

        assertEquals(first.certificate.encoded.toList(), second.certificate.encoded.toList())
        assertEquals(first.privateKey.encoded.toList(), second.privateKey.encoded.toList())
        assertTrue(keystore.isFile)
    }

    private fun signedFixture(): File {
        val source = File(temporary.root, "source.apk")
        TestApkFactory.build(source, TestManifests.standard())

        val key = SigningKeyStore.loadOrCreate(
            File(temporary.root, "clone.p12"),
            "fixture-password".toCharArray(),
        )
        val signed = File(temporary.root, "source-signed.apk")
        ApkSignatureSchemeV2.sign(source, signed, key)
        return signed
    }
}

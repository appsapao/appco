package com.yundac.apkcloner.core.sign

import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate

/**
 * Persists the clone signing key in a PKCS#12 keystore.
 *
 * All clones share one key so that a clone can be updated in place later. The
 * keystore is deliberately local and exportable: losing it only means that
 * already-installed clones can no longer be updated, not that anything leaks --
 * this key has no relationship to the original apps' signing identities.
 */
object SigningKeyStore {

    const val DEFAULT_ALIAS: String = "apkcloner"

    fun loadOrCreate(
        file: File,
        password: CharArray,
        alias: String = DEFAULT_ALIAS,
        commonName: String = "APK Cloner Clone Key",
    ): ApkSigningKey {
        if (file.isFile) {
            runCatching { load(file, password, alias) }.getOrNull()?.let { return it }
        }
        return create(file, password, alias, commonName)
    }

    private fun load(file: File, password: CharArray, alias: String): ApkSigningKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        file.inputStream().use { keyStore.load(it, password) }
        if (!keyStore.containsAlias(alias)) return null
        val key = keyStore.getKey(alias, password) as? PrivateKey ?: return null
        val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: return null
        return ApkSigningKey(key, certificate)
    }

    private fun create(file: File, password: CharArray, alias: String, commonName: String): ApkSigningKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE_BITS, SecureRandom())
        val keyPair = generator.generateKeyPair()
        val certificate = SelfSignedCertificate.create(keyPair.private, keyPair.public, commonName)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, password)
        keyStore.setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))

        file.parentFile?.mkdirs()
        // Write through a temporary file so an interrupted write cannot leave a
        // half-serialized keystore that would lock the app out of its own key.
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { keyStore.store(it, password) }
        if (file.exists()) file.delete()
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
        return ApkSigningKey(keyPair.private, certificate)
    }

    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEY_SIZE_BITS = 2048
}

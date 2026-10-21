package com.yundac.apkcloner.clone

import android.content.Context
import com.yundac.apkcloner.core.sign.ApkSigningKey
import com.yundac.apkcloner.core.sign.SigningKeyStore
import java.io.File
import java.security.SecureRandom

/**
 * Owns the signing key used for every clone on this device.
 *
 * One key for all clones means a clone can later be rebuilt and installed as an
 * update of itself. The key is generated locally on first use and never leaves
 * the app's private storage; it has no relationship to the original apps'
 * signing identities, which is exactly why signature-dependent features of a
 * clone stop working (see docs/LIMITATIONS.md).
 */
object SigningKeys {

    private const val PREFERENCES = "apk_cloner"
    private const val PASSWORD_KEY = "signing_password"

    @Volatile
    private var cached: ApkSigningKey? = null

    fun get(context: Context): ApkSigningKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val key = SigningKeyStore.loadOrCreate(
                file = keystoreFile(context),
                password = password(context).toCharArray(),
                commonName = "APK Cloner (local clone key)",
            )
            cached = key
            return key
        }
    }

    fun keystoreFile(context: Context): File = File(context.filesDir, "signing/clones.p12")

    private fun password(context: Context): String {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(PASSWORD_KEY, null)?.let { return it }

        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        val generated = random.joinToString("") { "%02x".format(it) }
        preferences.edit().putString(PASSWORD_KEY, generated).apply()
        return generated
    }
}

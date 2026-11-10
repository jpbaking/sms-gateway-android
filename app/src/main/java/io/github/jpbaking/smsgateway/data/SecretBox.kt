package io.github.jpbaking.smsgateway.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets at rest (the API key, webhook signing secrets, secret webhook headers).
 *
 * Uses an AES-256-GCM key that lives in the Android Keystore: the key never leaves secure
 * hardware (or the keystore daemon on phones without it), so a copy of the app's database or
 * preferences files is useless on its own. Values are stored as `enc:v1:<base64(iv + ciphertext)>`.
 * Anything without that prefix is treated as legacy plaintext, so data written by older builds
 * still reads fine.
 */
object SecretBox {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "gateway-secrets-v1"
    private const val PREFIX = "enc:v1:"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile
    private var cachedKey: SecretKey? = null

    fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(stored: String): String {
        if (!isEncrypted(stored)) return stored
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = (keyStore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
        cachedKey = key
        return key
    }
}

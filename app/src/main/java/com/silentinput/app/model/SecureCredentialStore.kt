package com.silentinput.app.model

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface CredentialCipher {
    fun encrypt(plaintext: String): String?
    fun decrypt(encoded: String): String?
}

/**
 * Pure-JVM AES-256-GCM credential cipher helper.
 */
class AesGcmKeyCipher(
    private val secretKey: SecretKeySpec
) : CredentialCipher {

    override fun encrypt(plaintext: String): String? {
        return try {
            val iv = ByteArray(GCM_IV_BYTES)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(ct, 0, packed, iv.size, ct.size)
            ENC_PREFIX_V1 + Base64.getEncoder().encodeToString(packed)
        } catch (_: Exception) {
            null
        }
    }

    override fun decrypt(encoded: String): String? {
        if (!encoded.startsWith(ENC_PREFIX_V1)) return null
        return try {
            val packed = Base64.getDecoder().decode(encoded.removePrefix(ENC_PREFIX_V1))
            if (packed.size <= GCM_IV_BYTES + GCM_TAG_BYTES) return null
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ct = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val ENC_PREFIX_V1 = "enc:v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
    }
}

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher for resting secrets in SharedPreferences.
 */
class AndroidKeystoreCipher(
    private val keyAlias: String = DEFAULT_ALIAS
) : CredentialCipher {

    @Volatile
    private var cachedUnavailable = false

    private fun loadOrCreateKey(recreate: Boolean = false): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            if (recreate && ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
            if (!recreate && ks.containsAlias(keyAlias)) {
                val existing = ks.getKey(keyAlias, null) as? SecretKey
                if (existing != null) return existing
            }
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGen.init(spec)
            keyGen.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable: ${e.message}")
            cachedUnavailable = true
            null
        }
    }

    override fun encrypt(plaintext: String): String? {
        if (cachedUnavailable) return null
        for (attempt in 0..1) {
            val key = loadOrCreateKey(recreate = attempt == 1) ?: return null
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                if (iv == null || iv.size != GCM_IV_BYTES) continue
                val packed = ByteArray(GCM_IV_BYTES + ct.size)
                System.arraycopy(iv, 0, packed, 0, GCM_IV_BYTES)
                System.arraycopy(ct, 0, packed, GCM_IV_BYTES, ct.size)
                return AesGcmKeyCipher.ENC_PREFIX_V1 + Base64.getEncoder().encodeToString(packed)
            } catch (e: Exception) {
                if (attempt == 0) {
                    Log.w(TAG, "Keystore encrypt failed, regenerating key once: ${e.message}")
                } else {
                    Log.w(TAG, "Keystore encrypt failed permanently: ${e.message}")
                }
            }
        }
        return null
    }

    override fun decrypt(encoded: String): String? {
        if (cachedUnavailable || !encoded.startsWith(AesGcmKeyCipher.ENC_PREFIX_V1)) return null
        val key = loadOrCreateKey(recreate = false) ?: return null
        return try {
            val packed = Base64.getDecoder().decode(encoded.removePrefix(AesGcmKeyCipher.ENC_PREFIX_V1))
            if (packed.size <= GCM_IV_BYTES + GCM_TAG_BYTES) return null
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ct = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SecureCredentialStore"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "silentinput_creds_aes_gcm_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
    }
}

/**
 * Encrypts sensitive preference values at rest and transparently migrates legacy plaintext entries.
 */
class SecureCredentialStore(
    private val cipher: CredentialCipher
) {
    fun readString(prefs: SharedPreferences, key: String, default: String = ""): String {
        val raw = prefs.getString(key, null) ?: return default
        if (raw.isEmpty()) return ""
        if (raw.startsWith(AesGcmKeyCipher.ENC_PREFIX_V1)) {
            return cipher.decrypt(raw) ?: default
        }
        // Migrate legacy plaintext value to encrypted form in-place
        val encrypted = cipher.encrypt(raw)
        if (encrypted != null) {
            prefs.edit().putString(key, encrypted).apply()
        }
        return raw
    }

    fun putString(editor: SharedPreferences.Editor, key: String, value: String) {
        if (value.isEmpty()) {
            editor.putString(key, "")
            return
        }
        val encrypted = cipher.encrypt(value)
        editor.putString(key, encrypted ?: value)
    }
}

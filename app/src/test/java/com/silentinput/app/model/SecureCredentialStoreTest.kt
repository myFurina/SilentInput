package com.silentinput.app.model

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SecureCredentialStoreTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }
            override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearAll) data.clear()
                for ((k, v) in pending) {
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }
    }

    private fun testCipher(): AesGcmKeyCipher {
        val raw = ByteArray(32) { i -> (i + 1).toByte() }
        return AesGcmKeyCipher(SecretKeySpec(raw, "AES"))
    }

    @Test
    fun `encrypts decrypts rejects tampering and migrates legacy plaintext`() {
        val prefs = InMemorySharedPreferences()
        val store = SecureCredentialStore(testCipher())

        // 1. Encrypt on write and decrypt cleanly on read
        val secret = "si_abcdefghijklmnopqrstuvwxyz0123456789ABCDE"
        prefs.edit().apply {
            store.putString(this, "auth_token", secret)
        }.apply()
        val storedRaw = prefs.getString("auth_token", null)!!
        assertTrue(storedRaw.startsWith(AesGcmKeyCipher.ENC_PREFIX_V1))
        assertFalse(storedRaw.contains(secret))
        assertEquals(secret, store.readString(prefs, "auth_token"))

        // 2. Two encryptions use distinct random IVs
        prefs.edit().apply {
            store.putString(this, "auth_token_2", secret)
        }.apply()
        assertNotEquals(storedRaw, prefs.getString("auth_token_2", null))

        // 3. Tampered ciphertext fails safely to default instead of leaking garbage
        prefs.edit().putString("auth_token", storedRaw.dropLast(4) + "AAAA").apply()
        assertEquals("", store.readString(prefs, "auth_token"))

        // 4. Legacy plaintext migrates in-place on first read
        val legacyJson = """[{"name":"pc","endpoint":"100.64.0.1:53318","token":"$secret"}]"""
        prefs.edit().putString("saved_lan_hosts_json", legacyJson).apply()
        assertEquals(legacyJson, store.readString(prefs, "saved_lan_hosts_json"))
        val migratedRaw = prefs.getString("saved_lan_hosts_json", null)!!
        assertTrue(migratedRaw.startsWith(AesGcmKeyCipher.ENC_PREFIX_V1))
        assertEquals(legacyJson, store.readString(prefs, "saved_lan_hosts_json"))

        // 5. Empty strings clear cleanly without encryption overhead
        prefs.edit().apply {
            store.putString(this, "auth_token", "")
        }.apply()
        assertEquals("", prefs.getString("auth_token", null))
        assertEquals("", store.readString(prefs, "auth_token"))
        assertNull(testCipher().decrypt("not-prefixed"))
    }
}

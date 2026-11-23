package com.fromsub.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyVault(private val context: Context) {

    private val keystoreAlias = "SubLaterHardwareKey_v1"
    private val fallbackPrefsName = "sublater_keystore_vault"
    private val encryptedPrefsFileName = "sublater_encrypted_api_keys"
    private val initLock = Any()
    private val warmupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SubLyter-KeyVaultInit").apply { isDaemon = true }
    }

    @Volatile
    private var prefsInitialized = false

    @Volatile
    private var cachedEncryptedPrefs: SharedPreferences? = null

    init {
        // Прогреваем Keystore/EncryptedSharedPreferences вне UI-потока, не тормозя onCreate()
        warmupExecutor.execute {
            ensureInitialized()
        }
    }

    private fun getEncryptedPrefs(): SharedPreferences? {
        if (prefsInitialized) return cachedEncryptedPrefs
        synchronized(initLock) {
            if (!prefsInitialized) {
                cachedEncryptedPrefs = try {
                    val masterKey = MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build()
                    EncryptedSharedPreferences.create(
                        context,
                        encryptedPrefsFileName,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                    )
                } catch (_: Exception) {
                    null
                }
                prefsInitialized = true
            }
            return cachedEncryptedPrefs
        }
    }

    @Volatile
    private var migrationChecked = false

    private fun ensureInitialized() {
        getEncryptedPrefs()
        if (migrationChecked) return
        synchronized(initLock) {
            if (migrationChecked) return
            try {
                val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
                if (!prefs.getBoolean("user_only_keystore_v17", false)) {
                    prefs.edit()
                        .remove("groq_api_key")
                        .putBoolean("user_only_keystore_v17", true)
                        .apply()
                }
                if (!prefs.getBoolean("migrated_to_esp_v18", false)) {
                    val existingGroq = getSecureKeyFallback("groq_api_key")
                    val existingGem = getSecureKeyFallback("gemini_api_key")
                    if (existingGroq.isNotBlank()) saveSecureKey("groq_api_key", existingGroq)
                    if (existingGem.isNotBlank()) saveSecureKey("gemini_api_key", existingGem)
                    prefs.edit().putBoolean("migrated_to_esp_v18", true).apply()
                }
            } catch (_: Exception) {
            } finally {
                migrationChecked = true
            }
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(keystoreAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keystoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveApiKey(key: String): Boolean = saveSecureKey("groq_api_key", key)

    fun getApiKey(): String = getSecureKey("groq_api_key")

    fun saveGeminiApiKey(key: String): Boolean = saveSecureKey("gemini_api_key", key)

    fun getGeminiApiKey(): String = getSecureKey("gemini_api_key")

    fun saveSecureKey(alias: String, plainValue: String): Boolean {
        val safeAlias = alias.trim()
        val cleanVal = plainValue.trim()
        try {
            val esp = getEncryptedPrefs()
            if (esp != null) {
                if (cleanVal.isEmpty()) {
                    esp.edit().remove(safeAlias).apply()
                } else {
                    esp.edit().putString(safeAlias, cleanVal).apply()
                }
                return true
            }
        } catch (_: Exception) {
        }
        return saveSecureKeyFallback(safeAlias, cleanVal)
    }

    fun getSecureKey(alias: String): String {
        val safeAlias = alias.trim()
        ensureInitialized()
        try {
            val esp = getEncryptedPrefs()
            if (esp != null) {
                val v = esp.getString(safeAlias, null)
                if (v != null) return v
            }
        } catch (_: Exception) {
        }
        return getSecureKeyFallback(safeAlias)
    }

    fun hasSecureKey(alias: String): Boolean = getSecureKey(alias).isNotBlank()

    private fun saveSecureKeyFallback(safeAlias: String, cleanVal: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
            if (cleanVal.isEmpty()) {
                prefs.edit().remove(safeAlias).apply()
                return true
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(cleanVal.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs.edit().putString(safeAlias, payload).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun getSecureKeyFallback(safeAlias: String): String {
        return try {
            val prefs = context.getSharedPreferences(fallbackPrefsName, Context.MODE_PRIVATE)
            val payload = prefs.getString(safeAlias, null) ?: return ""
            val parts = payload.split(":")
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    fun release() {
        warmupExecutor.shutdownNow()
    }
}

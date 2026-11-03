package com.kaliscriber.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.SecretProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's API keys (BYOK) encrypted with an AES-GCM key that never leaves the Android
 * Keystore. Keys are only ever sent to the provider they belong to.
 */
/** Credentials for export targets, stored next to the AI keys. */
enum class ExportSecret(val key: String) {
    NOTION_TOKEN("export.notion.token"),
    AFFINE_TOKEN("export.affine.token"),
    CALDAV_PASSWORD("export.caldav.password"),
    /** The password automatic backups are encrypted with. */
    BACKUP_PASSWORD("backup.password"),
}

class SecretStore(context: Context) : SecretProvider {

    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, String?>()
    private val _configured = MutableStateFlow(ProviderId.entries.filter { prefs.contains(it.name) }.toSet())
    private val _configuredSecrets = MutableStateFlow(ExportSecret.entries.filter { prefs.contains(it.key) }.toSet())

    /** Providers that currently have a key saved. */
    val configured: StateFlow<Set<ProviderId>> = _configured.asStateFlow()

    /** Export credentials (Notion, AFFiNE, CalDAV) that are saved. */
    val configuredSecrets: StateFlow<Set<ExportSecret>> = _configuredSecrets.asStateFlow()

    override fun apiKey(id: ProviderId): String? = read(id.name)

    fun setApiKey(id: ProviderId, key: String?) {
        val saved = write(id.name, key)
        _configured.value = if (saved) _configured.value + id else _configured.value - id
    }

    fun secret(secret: ExportSecret): String? = read(secret.key)

    fun setSecret(secret: ExportSecret, value: String?) {
        val saved = write(secret.key, value)
        _configuredSecrets.value = if (saved) _configuredSecrets.value + secret else _configuredSecrets.value - secret
    }

    @Synchronized
    private fun read(key: String): String? = cache.getOrPut(key) {
        prefs.getString(key, null)?.let { runCatching { decrypt(it) }.getOrNull() }
    }

    /** Stores (or with a blank value removes) a secret; returns whether one is now saved. */
    @Synchronized
    private fun write(key: String, value: String?): Boolean {
        val clean = value?.trim().orEmpty()
        return if (clean.isEmpty()) {
            prefs.edit().remove(key).apply()
            cache[key] = null
            false
        } else {
            prefs.edit().putString(key, encrypt(clean)).apply()
            cache[key] = clean
            true
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
        return String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kaliscriber_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

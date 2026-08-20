package cz.majkey.prepis

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun put(provider: CloudProvider, rawKey: String) {
        val key = validateApiKey(rawKey)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val encrypted = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
        val value = listOf(cipher.iv, encrypted).joinToString(SEPARATOR) {
            Base64.encodeToString(it, Base64.NO_WRAP)
        }
        preferences.edit { putString(provider.id, value) }
    }

    fun get(provider: CloudProvider): String? = runCatching {
        val parts = preferences.getString(provider.id, null)?.split(SEPARATOR) ?: return null
        if (parts.size != 2) return null

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
        }
        String(
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
            Charsets.UTF_8,
        ).let(::validateApiKey)
    }.getOrNull()

    fun remove(provider: CloudProvider) {
        preferences.edit { remove(provider.id) }
    }

    fun configuredProviders(): Set<CloudProvider> = CloudProvider.entries.filterTo(mutableSetOf()) {
        get(it) != null
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "api_keys"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "transcript_api_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SEPARATOR = "."
    }
}

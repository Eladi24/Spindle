package io.github.eladimany.spindle.data.smartplaylists

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.aiKeyStore: DataStore<Preferences> by preferencesDataStore("ai_key")

/**
 * The user's Gemini API key, encrypted with an Android Keystore key that never leaves
 * the phone. A backup restored elsewhere can't be decrypted — that reads as "no key",
 * and the user adds it again.
 */
@Singleton
class GeminiKeyStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefKey = stringPreferencesKey("gemini_key")

    val key: Flow<String?> = context.aiKeyStore.data.map { prefs -> prefs[prefKey]?.let(::decrypt) }

    suspend fun save(key: String) {
        val sealed = encrypt(key)
        context.aiKeyStore.edit { it[prefKey] = sealed }
    }

    suspend fun clear() {
        context.aiKeyStore.edit { it.remove(prefKey) }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** "iv:ciphertext", both Base64. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(sealed)
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, sealed) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(sealed), Charsets.UTF_8)
    } catch (e: Exception) {
        Timber.w(e, "Gemini key: couldn't decrypt — treating as no key")
        null
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "spindle_gemini_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

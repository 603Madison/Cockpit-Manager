package app.cockpitmanager.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Saved passwords. Screens only learn whether one is set; values go straight to the sign-in. */
interface SecretStore {
    fun get(name: String): String?
    fun has(name: String): Boolean
    fun put(name: String, value: String?)
}

/** For tests and previews. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun has(name: String) = name in values
    override fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) values.remove(name) else values[name] = value
    }
}

/**
 * Stores passwords encrypted with AES-256-GCM under a key held in the Android
 * Keystore (hardware-backed where available). The key can't be exported, so
 * the ciphertext is useless off this device; the backing file is also excluded
 * from backups. Each entry's name is bound as associated data so ciphertexts
 * can't be swapped between entries.
 */
class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun get(name: String): String? = synchronized(lock) {
        val stored = prefs.getString(name, null) ?: return null
        try {
            decrypt(name, stored)
        } catch (e: Exception) {
            // Delete only when the value can never be read again (key reset, e.g. after a lock-screen
            // change on some devices, or damaged data). A passing Keystore error keeps it for next time.
            val permanent = e is javax.crypto.AEADBadTagException ||
                e is android.security.keystore.KeyPermanentlyInvalidatedException ||
                e is IllegalArgumentException
            if (permanent) prefs.edit { remove(name) }
            null
        }
    }

    override fun has(name: String): Boolean = synchronized(lock) { prefs.contains(name) }

    override fun put(name: String, value: String?) = synchronized(lock) {
        if (value.isNullOrEmpty()) {
            prefs.edit { remove(name) }
        } else {
            prefs.edit { putString(name, encrypt(name, value)) }
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        // The key exists but couldn't be read just now: never generate a new one over it, which would
        // make every saved password unreadable.
        if (keyStore.containsAlias(KEY_ALIAS)) throw java.security.KeyStoreException("Keystore key temporarily unavailable")
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(name: String, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "v1:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(ciphertext)}"
    }

    private fun decrypt(name: String, stored: String): String {
        val parts = stored.split(':')
        require(parts.size == 3 && parts[0] == "v1") { "Unknown secret format" }
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, decoder.decode(parts[1])))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(decoder.decode(parts[2])), Charsets.UTF_8)
    }

    private companion object {
        private const val FILE = "secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cockpitmanager_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

package com.palli.mobile.data.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableEntryException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals the device token at rest. EncryptedSharedPreferences is deprecated; this is the same idea, done directly. */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(blob: String): String
}

/** Where the sealing key lives: one key under one alias. [stored] is null when there is none, and throws when it cannot be read. */
internal interface KeyVault {
    fun stored(): SecretKey?
    fun delete()
    fun generate(): SecretKey
}

/** AES-256-GCM under a key that lives in Android Keystore and never leaves it. Blob = base64(iv):base64(ciphertext). */
class KeystoreTokenCipher internal constructor(private val vault: KeyVault) : TokenCipher {
    constructor() : this(AndroidKeyVault)

    /**
     * The key, made on first use. One caller at a time: two first callers must not each make one, since the second
     * replaces the first under the alias and a token sealed under the first would never unseal.
     *
     * A key that is there but cannot be read (Android may drop it: a Keystore reset, a lock-screen change on some phones)
     * is deleted and made again. Whatever it sealed is unreadable either way; this way the phone can pair again, and
     * SessionStore.load says the key was lost. Kept, it would refuse every pairing until the app's data was cleared.
     */
    @Synchronized
    private fun key(): SecretKey {
        val stored = try {
            vault.stored()
        } catch (e: UnrecoverableEntryException) {          // UnrecoverableKeyException is one
            forget(e)
        } catch (e: KeyStoreException) {
            forget(e)
        }
        return stored ?: vault.generate()
    }

    private fun forget(e: Exception): SecretKey? {
        Log.w(TAG, "the token key could not be read (${e.javaClass.simpleName}); making a new one")
        try {
            vault.delete()
        } catch (e: Exception) {
            Log.w(TAG, "the unreadable key could not be deleted: ${e.javaClass.simpleName}")
        }
        return null
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    override fun decrypt(blob: String): String {
        val (iv, sealed) = blob.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    private companion object {
        const val TAG = "TokenCipher"
    }
}

private object AndroidKeyVault : KeyVault {
    private const val ALIAS = "palli_device_token"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun stored(): SecretKey? = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    override fun delete() = keyStore().deleteEntry(ALIAS)

    override fun generate(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

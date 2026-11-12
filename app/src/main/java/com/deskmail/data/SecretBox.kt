package com.deskmail.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Something sealed by a [SecretBox]: the ciphertext and the nonce it was made with. Neither is secret. */
class Sealed(val iv: ByteArray, val data: ByteArray)

/**
 * Seals what an account signs in with before it is stored, and opens it again.
 *
 * [purpose] says what the secret is for ("credential/3"). It is not stored with the secret, and a
 * secret only opens for the purpose it was sealed for: a row copied onto another account's does not.
 */
interface SecretBox {
    fun seal(plain: ByteArray, purpose: String): Sealed

    /** @throws java.security.GeneralSecurityException when it was not sealed by this box, for this purpose. */
    fun open(sealed: Sealed, purpose: String): ByteArray
}

/** AES-256 in GCM mode with the key [key] hands out; the nonce is new for every seal. */
open class AesGcmBox(private val key: () -> SecretKey) : SecretBox {

    override fun seal(plain: ByteArray, purpose: String): Sealed {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // no nonce given: the cipher makes a random one, which the device's keystore insists on
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(plain)
        return Sealed(cipher.iv, data)
    }

    override fun open(sealed: Sealed, purpose: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed.data)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/**
 * The box the app uses: its key is made in the device's keystore on first use and never leaves it.
 * Nothing outside this device can open what it sealed; the app is not backed up either, so a new
 * device starts with no accounts and the mail comes from the servers again.
 */
class DeviceSecretBox(alias: String = KEY_ALIAS) : AesGcmBox({ deviceKey(alias) }) {

    companion object {
        /** The key's name in the keystore. Never changes: what was sealed under it would not open again. */
        const val KEY_ALIAS = "deskmail.credentials.1"

        private const val KEYSTORE = "AndroidKeyStore"

        @Synchronized
        private fun deviceKey(alias: String): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}

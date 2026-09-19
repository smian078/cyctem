package com.cystem.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(context: Context) {
    init {
        requireNotNull(context.applicationContext)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plainText: String): String {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val payload = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(1 + iv.size + payload.size)
                    .put(iv.size.toByte())
                    .put(iv)
                    .put(payload)
                    .array(),
            )
        } catch (e: Exception) {
            throw SecureStorageException("Could not encrypt API key", e)
        }
    }

    fun decrypt(encoded: String): String {
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.isNotEmpty()) { "Empty encrypted payload" }
            val ivLength = bytes[0].toInt() and 0xFF
            require(ivLength in 12..16) { "Invalid IV length" }
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val payload = bytes.copyOfRange(1 + ivLength, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            return cipher.doFinal(payload).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw SecureStorageException("Could not decrypt stored secret", e)
        }
    }

    fun deleteKeyMaterial() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            throw SecureStorageException("Could not remove secure key material", e)
        }
    }

    fun isHardwareBacked(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val stored = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return false
            val factory = SecretKeyFactory.getInstance(stored.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(
                stored,
                android.security.keystore.KeyInfo::class.java,
            ) as android.security.keystore.KeyInfo
            info.isInsideSecureHardware
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cystem.master.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

class SecureStorageException(message: String, cause: Throwable) : RuntimeException(message, cause)

package com.minids.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 里的 AES-256 密钥加密 API Key。
 * 密钥不可导出，备份到其他设备也无法解密。
 */
object KeyCrypto {

    private const val ALIAS = "minids_api_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val packed = byteArrayOf(iv.size.toByte()) + iv + body
            Base64.encodeToString(packed, Base64.NO_WRAP)
        }.getOrDefault("")
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            if (raw.size < 3) return ""
            val ivLen = raw[0].toInt() and 0xFF
            if (raw.size <= 1 + ivLen) return ""
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val body = raw.copyOfRange(1 + ivLen, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }
}

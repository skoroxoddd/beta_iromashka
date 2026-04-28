package com.iromashka.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group E2E encryption using shared AES-256-GCM key.
 *
 * Flow:
 * 1. Creator generates AES-256 group key
 * 2. Key is encrypted for each member using ECDH (their pubkey)
 * 3. Members decrypt group key with their privkey
 * 4. Messages encrypted with group key
 */
object GroupCrypto {

    /**
     * Generate a new AES-256 group key
     */
    fun generateGroupKey(): SecretKey {
        val kg = KeyGenerator.getInstance("AES")
        kg.init(256, SecureRandom())
        return kg.generateKey()
    }

    /**
     * Export group key to base64
     */
    fun exportGroupKey(key: SecretKey): String {
        return Base64.encodeToString(key.encoded, Base64.NO_WRAP)
    }

    /**
     * Import group key from base64
     */
    fun importGroupKey(b64: String): SecretKey {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        return SecretKeySpec(raw, "AES")
    }

    /**
     * Encrypt group key for a member using their public key.
     * Uses V2 envelope format (same as CryptoManager.encryptMessage).
     */
    fun encryptGroupKeyForMember(groupKeyB64: String, memberPubKeyB64: String): String? {
        return runCatching {
            val memberPub = CryptoManager.importPublicKey(memberPubKeyB64)
            CryptoManager.encryptMessage(groupKeyB64, memberPub)
        }.getOrNull()
    }

    /**
     * Decrypt group key that was encrypted for us.
     */
    fun decryptGroupKey(encryptedKey: String, myPrivKey: java.security.PrivateKey): String? {
        return CryptoManager.decryptMessage(encryptedKey, myPrivKey)
    }

    /**
     * Encrypt message with group key.
     * Format: base64(iv(12) || ciphertext)
     */
    fun encryptWithGroupKey(plaintext: String, groupKey: SecretKey): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, groupKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val result = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ct, 0, result, iv.size, ct.size)
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    /**
     * Decrypt message with group key.
     */
    fun decryptWithGroupKey(ciphertext: String, groupKey: SecretKey): String? {
        return runCatching {
            val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (raw.size < 29) return null // 12 iv + 16 tag + 1 min data
            val iv = raw.sliceArray(0 until 12)
            val ct = raw.sliceArray(12 until raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, groupKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}

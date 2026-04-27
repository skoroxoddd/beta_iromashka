package com.iromashka.crypto

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/**
 * BIP39 + recovery wrap, совместимо с PWA (index.html: bip39Generate / recoveryDeriveKey).
 *
 * Phrase: 12 слов из BIP39 wordlist (assets/bip39.txt). Checksum пропущен —
 * wordlist используется только как источник энтропии для derive key, не как BIP32 seed.
 *
 * Derive (matches PWA recoveryDeriveKey):
 *   norm = lowercase + collapse spaces
 *   aes  = PBKDF2(norm, salt="irm-rec/<uin>", 250000, SHA-256, 256bit)
 *   fp   = PBKDF2(norm, salt="irm-fp/<uin>",  50000,  SHA-256, 256bit) → hex
 *
 * Wrap: AES-GCM(privKey.pkcs8, key=aes, iv=rand12). Blob = iv || ct, base64.
 */
object Bip39 {

    private const val PBKDF2_REC_ITERS = 250_000
    private const val PBKDF2_FP_ITERS = 50_000

    @Volatile private var wordsCache: List<String>? = null
    @Volatile private var wordSetCache: Set<String>? = null

    fun loadWordlist(ctx: Context): List<String> {
        wordsCache?.let { return it }
        val list = ctx.assets.open("bip39.txt").bufferedReader().useLines { it.toList() }
        require(list.size == 2048) { "BIP39 wordlist size ${list.size} != 2048" }
        wordsCache = list
        wordSetCache = list.toHashSet()
        return list
    }

    fun generate(ctx: Context): String {
        val words = loadWordlist(ctx)
        val buf = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val out = ArrayList<String>(12)
        for (i in 0 until 12) {
            val bitOffset = i * 11
            val byteOffset = bitOffset / 8
            val shift = bitOffset % 8
            val b0 = buf[byteOffset].toInt() and 0xFF
            val b1 = if (byteOffset + 1 < buf.size) buf[byteOffset + 1].toInt() and 0xFF else 0
            val b2 = if (byteOffset + 2 < buf.size) buf[byteOffset + 2].toInt() and 0xFF else 0
            val v = (b0 shl 16) or (b1 shl 8) or b2
            val idx = (v shr (24 - shift - 11)) and 0x7FF
            out.add(words[idx])
        }
        return out.joinToString(" ")
    }

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Returns normalized phrase if valid, null otherwise. */
    fun validate(ctx: Context, s: String): String? {
        val norm = normalize(s)
        val parts = norm.split(' ')
        if (parts.size != 12) return null
        loadWordlist(ctx)
        val set = wordSetCache ?: return null
        for (w in parts) if (w !in set) return null
        return parts.joinToString(" ")
    }

    data class DerivedKey(val aes: SecretKey, val fingerprint: String, val normalized: String)

    fun deriveKey(ctx: Context, phrase: String, uin: Long): DerivedKey {
        val norm = validate(ctx, phrase) ?: throw IllegalArgumentException("Фраза должна состоять из 12 слов BIP39")
        val recSalt = "irm-rec/$uin".toByteArray(Charsets.UTF_8)
        val fpSalt  = "irm-fp/$uin".toByteArray(Charsets.UTF_8)
        val aesRaw = pbkdf2(norm, recSalt, PBKDF2_REC_ITERS, 256)
        val fpRaw  = pbkdf2(norm, fpSalt,  PBKDF2_FP_ITERS,  256)
        return DerivedKey(
            aes = SecretKeySpec(aesRaw, "AES"),
            fingerprint = fpRaw.joinToString("") { "%02x".format(it) },
            normalized = norm
        )
    }

    data class Wrapped(val wrappedB64: String, val fingerprint: String)

    fun wrap(ctx: Context, privKey: PrivateKey, phrase: String, uin: Long): Wrapped {
        val d = deriveKey(ctx, phrase, uin)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, d.aes, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(privKey.encoded)
        val blob = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(ct, 0, blob, iv.size, ct.size)
        return Wrapped(
            wrappedB64 = Base64.encodeToString(blob, Base64.NO_WRAP),
            fingerprint = d.fingerprint
        )
    }

    fun unwrap(ctx: Context, wrappedB64: String, phrase: String, uin: Long): PrivateKey {
        val d = deriveKey(ctx, phrase, uin)
        val blob = Base64.decode(wrappedB64, Base64.NO_WRAP)
        require(blob.size > 12) { "Поврежденная резервная копия" }
        val iv = blob.sliceArray(0 until 12)
        val ct = blob.sliceArray(12 until blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, d.aes, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun pbkdf2(passphrase: String, salt: ByteArray, iters: Int, bits: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iters, bits)
        return factory.generateSecret(spec).encoded
    }
}

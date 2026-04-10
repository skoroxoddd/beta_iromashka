package com.iromashka.crypto

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

/**
 * E2E шифрование — совместимо с сервером v0.10.0.
 * - P-256 ECDH keypair
 * - Private key: PBKDF2(pin, 250k iter) + AES-256-GCM wrap
 * - Per-message: ephemeral ECDH + HKDF-SHA256 + AES-256-GCM
 * - Forward Secrecy: ephemeral key discarded after each message
 */
object CryptoManager {

    private const val PBKDF2_ITERATIONS = 250_000
    private const val EC_CURVE = "secp256r1"
    private const val HKDF_INFO = "iromashka-msg"
    private val gson = Gson()

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        return kpg.generateKeyPair()
    }

    fun exportPublicKey(pub: PublicKey): String =
        Base64.encodeToString(pub.encoded, Base64.NO_WRAP)

    fun importPublicKey(b64: String): PublicKey {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun wrapPrivateKey(priv: PrivateKey, pin: String): String {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(priv.encoded)
        return Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
    }

    fun unwrapPrivateKey(wrapped: String, pin: String): PrivateKey {
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        val salt = bytes.sliceArray(0..15)
        val iv = bytes.sliceArray(16..27)
        val ct = bytes.sliceArray(28 until bytes.size)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    fun rewrapPrivateKey(wrapped: String, oldPin: String, newPin: String): String {
        val priv = unwrapPrivateKey(wrapped, oldPin)
        return wrapPrivateKey(priv, newPin)
    }

    fun encryptMessage(plaintext: String, recipientPub: PublicKey): String {
        val ephKp = generateKeyPair()

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(ephKp.private)
        ka.doPhase(recipientPub, true)
        val sharedSecret = ka.generateSecret()

        val aesKeyBytes = hkdf(sharedSecret, HKDF_INFO.toByteArray(Charsets.UTF_8))
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")

        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return gson.toJson(mapOf(
            "v" to 1,
            "epk" to Base64.encodeToString(ephKp.public.encoded, Base64.NO_WRAP),
            "iv" to Base64.encodeToString(iv, Base64.NO_WRAP),
            "ct" to Base64.encodeToString(ct, Base64.NO_WRAP)
        ))
    }

    fun decryptMessage(cipherJson: String, myPrivKey: PrivateKey): String? = runCatching {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = gson.fromJson(cipherJson, type)

        val ephPubBytes = Base64.decode(map["epk"] as String, Base64.NO_WRAP)
        val iv = Base64.decode(map["iv"] as String, Base64.NO_WRAP)
        val ct = Base64.decode(map["ct"] as String, Base64.NO_WRAP)

        val ephPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephPubBytes))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivKey)
        ka.doPhase(ephPub, true)
        val sharedSecret = ka.generateSecret()

        val aesKeyBytes = hkdf(sharedSecret, HKDF_INFO.toByteArray(Charsets.UTF_8))
        val aesKey = SecretKeySpec(aesKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun verifyPin(wrapped: String, pin: String): Boolean = runCatching {
        unwrapPrivateKey(wrapped, pin)
    }.isSuccess

    // ── Internals ──────────────────────────────────────────────────────────

    private fun pbkdf2Key(pin: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(0x01.toByte())
        return mac.doFinal()
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        System.arraycopy(this, 0, result, 0, size)
        System.arraycopy(other, 0, result, size, other.size)
        return result
    }
}

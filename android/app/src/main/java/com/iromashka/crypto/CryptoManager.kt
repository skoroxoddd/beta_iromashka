package com.iromashka.crypto

import android.util.Base64
import org.json.JSONObject
import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

/**
 * E2E crypto compatible with web client (WebCrypto API):
 * - P-256 ECDH keypair
 * - Private key: PBKDF2(pin, 250k iter) + AES-256-GCM wrap
 * - Per-message: ephemeral ECDH + raw shared secret + AES-256-GCM
 * - Forward Secrecy: ephemeral key discarded after each message
 */
object CryptoManager {

    private const val PBKDF2_ITERATIONS = 250_000
    private const val EC_CURVE = "secp256r1"

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

    // ── Private key wrapping ────────────────────────────────────────────────

    /**
     * Wrap private key with PIN.
     * Android-native compact format: base64( salt[16] || iv[12] || ciphertext ).
     */
    fun wrapPrivateKey(priv: PrivateKey, pin: String): String {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(priv.encoded)
        return Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
    }

    /**
     * Wrap private key in PWA-compatible JSON format.
     * Format: {"ct": base64(aes-gcm-wrapped-pkcs8), "iv": base64(iv), "salt": base64(salt)}
     * This matches what PWA saves to /api/save-key for cross-device recovery.
     */
    fun wrapPrivateKeyPwa(priv: PrivateKey, pin: String): Pair<String, String> {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(priv.encoded)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val json = JSONObject()
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("salt", saltB64)
            .toString()
        return Pair(json, saltB64)
    }

    /**
     * Unwrap private key with PIN. Auto-detects format:
     * - PWA JSON: {"ct":..., "iv":..., "salt":...}
     * - Android compact: base64(salt||iv||ct)
     */
    fun unwrapPrivateKey(wrapped: String, pin: String): PrivateKey {
        val trimmed = wrapped.trim()
        return if (trimmed.startsWith("{")) {
            unwrapPrivateKeyJson(trimmed, pin)
        } else {
            unwrapPrivateKeyCompact(trimmed, pin)
        }
    }

    private fun unwrapPrivateKeyCompact(wrapped: String, pin: String): PrivateKey {
        val bytes = Base64.decode(wrapped, Base64.NO_WRAP)
        if (bytes.size < 28) throw IllegalArgumentException("wrapped key too short")
        val salt = bytes.sliceArray(0..15)
        val iv = bytes.sliceArray(16..27)
        val ct = bytes.sliceArray(28 until bytes.size)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun unwrapPrivateKeyJson(json: String, pin: String): PrivateKey {
        val obj = JSONObject(json)
        val salt = Base64.decode(obj.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(obj.getString("ct"), Base64.NO_WRAP)
        val aesKey = pbkdf2Key(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    fun rewrapPrivateKey(wrapped: String, oldPin: String, newPin: String): String =
        wrapPrivateKey(unwrapPrivateKey(wrapped, oldPin), newPin)

    // ── Message encryption ─────────────────────────────────────────────────

    /**
     * Encrypt for a single recipient.
     * Format: {"v":2,"epk":"...","iv":"...","ct":"..."}
     * Uses raw ECDH shared secret as AES key — matches WebCrypto deriveKey.
     */
    fun encryptMessage(plaintext: String, recipientPub: PublicKey): String {
        val ephKp = generateKeyPair()
        val aesKey = ecdhAesKey(ephKp.private, recipientPub)
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val json = JSONObject()
        json.put("v", 2)
        json.put("epk", Base64.encodeToString(ephKp.public.encoded, Base64.NO_WRAP))
        json.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
        json.put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
        return json.toString()
    }

    fun encryptForDevices(plaintext: String, devices: List<DeviceInfo>): List<DevicePayload> =
        devices.mapNotNull { dev ->
            runCatching {
                val pub = importPublicKey(dev.pubkey)
                DevicePayload(device_id = dev.device_id, ciphertext = encryptMessage(plaintext, pub))
            }.getOrNull()
        }

    fun decryptMessage(cipherJson: String, myPrivKey: PrivateKey): String? = runCatching {
        val obj = JSONObject(cipherJson)
        val ephPubBytes = Base64.decode(obj.getString("epk"), Base64.NO_WRAP)
        val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(obj.getString("ct"), Base64.NO_WRAP)
        val ephPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephPubBytes))
        val aesKey = ecdhAesKey(myPrivKey, ephPub)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun verifyPin(wrapped: String, pin: String): Boolean = runCatching {
        unwrapPrivateKey(wrapped, pin)
    }.isSuccess

    // ── Internal ────────────────────────────────────────────────────────────

    private fun ecdhAesKey(priv: PrivateKey, pub: PublicKey): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val shared = ka.generateSecret()
        return SecretKeySpec(shared.copyOf(32), "AES")
    }

    private fun pbkdf2Key(pin: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun randomBytes(n: Int): ByteArray =
        ByteArray(n).also { SecureRandom().nextBytes(it) }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val result = ByteArray(size + other.size)
        System.arraycopy(this, 0, result, 0, size)
        System.arraycopy(other, 0, result, size, other.size)
        return result
    }

    data class DeviceInfo(val device_id: String, val pubkey: String)
    data class DevicePayload(val device_id: String, val ciphertext: String)
}

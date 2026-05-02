package com.iromashka.crypto

import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
import org.json.JSONObject
import java.security.*
import java.security.spec.*
import javax.crypto.*
import javax.crypto.spec.*

/**
 * E2E crypto compatible with web client (WebCrypto API):
 * - P-256 ECDH keypair
 * - Private key: Argon2id (m=19MiB t=2 p=1) + AES-256-GCM wrap (C3, "ARG1" prefix)
 *   Legacy reader: PBKDF2(pin, 250k iter) — для blob-ов без префикса
 * - Per-message: ephemeral ECDH + raw shared secret + AES-256-GCM
 * - Forward Secrecy: ephemeral key discarded after each message
 */
object CryptoManager {

    private const val PBKDF2_ITERATIONS = 250_000
    private const val EC_CURVE = "secp256r1"

    // C3: Argon2id parameters — должны 1-в-1 совпадать с PWA (deriveArgon2idAesKey)
    private const val ARGON2_T_COST = 2
    private const val ARGON2_M_COST_KIB = 19_456 // 19 MiB
    private const val ARGON2_PARALLELISM = 1
    private const val ARGON2_HASH_LEN = 32
    // "ARG1" в ASCII
    private val ARG1_PREFIX = byteArrayOf(0x41, 0x52, 0x47, 0x31)

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

    /** C3: write Argon2id-wrapped blob with "ARG1" prefix. Format: ARG1 || salt(16) || iv(12) || ct */
    fun wrapPrivateKey(priv: PrivateKey, pin: String): String {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val aesKey = argon2idKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(priv.encoded)
        return Base64.encodeToString(ARG1_PREFIX + salt + iv + ct, Base64.NO_WRAP)
    }

    fun unwrapPrivateKey(wrapped: String, pin: String): PrivateKey {
        // Поддерживаемые форматы:
        //   1. C3 ARG1: base64( "ARG1"(4) || salt(16) || iv(12) || ct )
        //   2. Legacy compact: base64( salt(16) || iv(12) || ct ) — PBKDF2-250k
        //   3. PWA JSON {"ct","iv","salt"} — legacy PBKDF2 PWA backup
        val trimmed = wrapped.trim()
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val salt = Base64.decode(obj.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(obj.getString("iv"), Base64.NO_WRAP)
            val ct = Base64.decode(obj.getString("ct"), Base64.NO_WRAP)
            val aesKey = pbkdf2Key(pin, salt)
            return decryptPkcs8(aesKey, iv, ct)
        }
        val bytes = Base64.decode(trimmed, Base64.NO_WRAP)
        if (hasArg1Prefix(bytes)) {
            require(bytes.size > 4 + 16 + 12 + 16) { "ARG1 wrapped too short" }
            val salt = bytes.sliceArray(4 until 20)
            val iv = bytes.sliceArray(20 until 32)
            val ct = bytes.sliceArray(32 until bytes.size)
            val aesKey = argon2idKey(pin, salt)
            return decryptPkcs8(aesKey, iv, ct)
        }
        // legacy PBKDF2
        require(bytes.size > 28) { "wrapped too short" }
        val salt = bytes.sliceArray(0..15)
        val iv = bytes.sliceArray(16..27)
        val ct = bytes.sliceArray(28 until bytes.size)
        val aesKey = pbkdf2Key(pin, salt)
        return decryptPkcs8(aesKey, iv, ct)
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

    private fun decryptPkcs8(aesKey: SecretKey, iv: ByteArray, ct: ByteArray): PrivateKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun hasArg1Prefix(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == ARG1_PREFIX[0] && b[1] == ARG1_PREFIX[1] &&
            b[2] == ARG1_PREFIX[2] && b[3] == ARG1_PREFIX[3]

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

    private fun argon2idKey(pin: String, salt: ByteArray): SecretKey {
        val raw = Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = pin.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = ARGON2_T_COST,
            mCostInKibibyte = ARGON2_M_COST_KIB,
            parallelism = ARGON2_PARALLELISM,
            hashLengthInBytes = ARGON2_HASH_LEN
        ).rawHashAsByteArray()
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

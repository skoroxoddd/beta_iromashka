package com.iromashka.crypto

import android.content.Context
import android.util.Base64
import com.lambdapioneer.argon2kt.Argon2Kt
import com.lambdapioneer.argon2kt.Argon2Mode
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
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.math.BigInteger
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.security.interfaces.ECPrivateKey
import java.security.AlgorithmParameters

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
 *
 * C3: unwrap is reader-tolerant — supports legacy (iv|ct, PBKDF2) and ARG1
 * (ARG1 || iv(12) || ct, Argon2id). Wrap remains PBKDF2 until PWA recovery
 * is migrated; coordinated upgrade later.
 */
object Bip39 {

    private const val PBKDF2_REC_ITERS = 250_000
    private const val PBKDF2_FP_ITERS = 50_000

    // Argon2id (matches CryptoManager / PWA derivation)
    private const val ARGON2_T_COST = 2
    private const val ARGON2_M_COST_KIB = 19_456
    private const val ARGON2_PARALLELISM = 1
    private const val ARGON2_HASH_LEN = 32
    private val ARG1_PREFIX = byteArrayOf(0x41, 0x52, 0x47, 0x31)

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

    /** C3: Argon2id-derived AES key for ARG1 recovery blobs. Salt namespace отличается от
     *  PBKDF2 ("irm-rec-arg/<uin>"), чтобы случайно не пересеклись. PWA при миграции
     *  должен использовать ту же строку. */
    private fun deriveArgon2idAesKey(phrase: String, uin: Long): SecretKey {
        val salt = "irm-rec-arg/$uin".toByteArray(Charsets.UTF_8)
        val raw = Argon2Kt().hash(
            mode = Argon2Mode.ARGON2_ID,
            password = phrase.toByteArray(Charsets.UTF_8),
            salt = salt,
            tCostInIterations = ARGON2_T_COST,
            mCostInKibibyte = ARGON2_M_COST_KIB,
            parallelism = ARGON2_PARALLELISM,
            hashLengthInBytes = ARGON2_HASH_LEN
        ).rawHashAsByteArray()
        return SecretKeySpec(raw, "AES")
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
        val norm = validate(ctx, phrase) ?: throw IllegalArgumentException("Фраза должна состоять из 12 слов BIP39")
        val blob = Base64.decode(wrappedB64, Base64.NO_WRAP)
        if (hasArg1Prefix(blob)) {
            // C3 ARG1: ARG1(4) || iv(12) || ct
            require(blob.size > 4 + 12 + 16) { "ARG1 recovery blob too short" }
            val iv = blob.sliceArray(4 until 16)
            val ct = blob.sliceArray(16 until blob.size)
            val aes = deriveArgon2idAesKey(norm, uin)
            return decryptPkcs8(aes, iv, ct)
        }
        // legacy PBKDF2: iv(12) || ct
        require(blob.size > 12) { "Поврежденная резервная копия" }
        val iv = blob.sliceArray(0 until 12)
        val ct = blob.sliceArray(12 until blob.size)
        val d = deriveKey(ctx, norm, uin)
        return decryptPkcs8(d.aes, iv, ct)
    }

    private fun decryptPkcs8(aes: SecretKey, iv: ByteArray, ct: ByteArray): PrivateKey {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, iv))
        val pkcs8 = cipher.doFinal(ct)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }

    private fun hasArg1Prefix(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == ARG1_PREFIX[0] && b[1] == ARG1_PREFIX[1] &&
            b[2] == ARG1_PREFIX[2] && b[3] == ARG1_PREFIX[3]

    private fun pbkdf2(passphrase: String, salt: ByteArray, iters: Int, bits: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iters, bits)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Deterministically derive a P-256 keypair from BIP39 phrase + UIN.
     * Same phrase + UIN = same keypair on any device.
     *
     * Method: PBKDF2(phrase, "irm-ec/<uin>", 250000) → 32 bytes → EC private key scalar
     * Public key derived from private key using standard EC point multiplication.
     */
    fun deriveKeyPair(ctx: Context, phrase: String, uin: Long): KeyPair {
        val norm = validate(ctx, phrase) ?: throw IllegalArgumentException("Фраза должна состоять из 12 слов BIP39")
        val ecSalt = "irm-ec/$uin".toByteArray(Charsets.UTF_8)
        val privBytes = pbkdf2(norm, ecSalt, PBKDF2_REC_ITERS, 256)

        // Get P-256 curve parameters
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val ecParams = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)

        // Ensure private key is in valid range [1, n-1] where n is curve order
        var s = BigInteger(1, privBytes)
        val n = ecParams.order
        s = s.mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE)

        // Create private key
        val privKeySpec = ECPrivateKeySpec(s, ecParams)
        val kf = KeyFactory.getInstance("EC")
        val privKey = kf.generatePrivate(privKeySpec) as ECPrivateKey

        // Derive public key: pubKey = privKey * G (generator point)
        // Use KeyPairGenerator to get public key from private scalar
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))

        // Calculate public point manually: Q = d * G
        val g = ecParams.generator
        val q = scalarMultiply(g, s, ecParams)
        val pubKeySpec = ECPublicKeySpec(q, ecParams)
        val pubKey = kf.generatePublic(pubKeySpec)

        return KeyPair(pubKey, privKey)
    }

    // EC point scalar multiplication using double-and-add
    private fun scalarMultiply(point: ECPoint, scalar: BigInteger, params: java.security.spec.ECParameterSpec): ECPoint {
        val curve = params.curve
        val p = curve.field.let { (it as java.security.spec.ECFieldFp).p }

        var result = ECPoint.POINT_INFINITY
        var addend = point
        var k = scalar

        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = pointAdd(result, addend, p, curve.a)
            }
            addend = pointDouble(addend, p, curve.a)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun pointAdd(p1: ECPoint, p2: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (p1 == ECPoint.POINT_INFINITY) return p2
        if (p2 == ECPoint.POINT_INFINITY) return p1

        val x1 = p1.affineX
        val y1 = p1.affineY
        val x2 = p2.affineX
        val y2 = p2.affineY

        if (x1 == x2) {
            return if (y1 == y2) pointDouble(p1, p, a)
            else ECPoint.POINT_INFINITY
        }

        val slope = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p)
        val x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(p)
        val y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(p)

        return ECPoint(x3, y3)
    }

    private fun pointDouble(pt: ECPoint, p: BigInteger, a: BigInteger): ECPoint {
        if (pt == ECPoint.POINT_INFINITY) return pt

        val x = pt.affineX
        val y = pt.affineY

        if (y.signum() == 0) return ECPoint.POINT_INFINITY

        val three = BigInteger.valueOf(3)
        val two = BigInteger.valueOf(2)

        val slope = x.multiply(x).multiply(three).add(a)
            .multiply(y.multiply(two).modInverse(p)).mod(p)
        val x3 = slope.multiply(slope).subtract(x.multiply(two)).mod(p)
        val y3 = slope.multiply(x.subtract(x3)).subtract(y).mod(p)

        return ECPoint(x3, y3)
    }
}

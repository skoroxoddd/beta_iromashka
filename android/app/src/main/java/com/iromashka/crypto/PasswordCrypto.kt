package com.iromashka.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.ECPoint
import java.security.AlgorithmParameters
import java.math.BigInteger
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password-based key derivation for E2E encryption.
 *
 * Derives deterministic P-256 keypair from password + UIN.
 * Same password + UIN = same keypair on any device.
 *
 * Requirements:
 * - Password: 12-20 characters
 * - At least 1 uppercase, 1 lowercase, 1 digit, 1 special char
 */
object PasswordCrypto {

    private const val PBKDF2_ITERATIONS = 250_000
    private const val MIN_PASSWORD_LENGTH = 12
    private const val MAX_PASSWORD_LENGTH = 20

    data class PasswordValidation(
        val isValid: Boolean,
        val error: String? = null
    )

    /**
     * Validate password requirements:
     * - 12-20 characters
     * - At least 1 uppercase letter
     * - At least 1 lowercase letter
     * - At least 1 digit
     * - At least 1 special character
     */
    fun validatePassword(password: String): PasswordValidation {
        if (password.length < MIN_PASSWORD_LENGTH) {
            return PasswordValidation(false, "Минимум $MIN_PASSWORD_LENGTH символов")
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            return PasswordValidation(false, "Максимум $MAX_PASSWORD_LENGTH символов")
        }
        if (!password.any { it.isUpperCase() }) {
            return PasswordValidation(false, "Нужна хотя бы 1 заглавная буква")
        }
        if (!password.any { it.isLowerCase() }) {
            return PasswordValidation(false, "Нужна хотя бы 1 строчная буква")
        }
        if (!password.any { it.isDigit() }) {
            return PasswordValidation(false, "Нужна хотя бы 1 цифра")
        }
        if (!password.any { !it.isLetterOrDigit() }) {
            return PasswordValidation(false, "Нужен хотя бы 1 спецсимвол (!@#\$%^&*)")
        }
        return PasswordValidation(true)
    }

    /**
     * Derive deterministic P-256 keypair from password + UIN.
     */
    fun deriveKeyPair(password: String, uin: Long): KeyPair {
        val validation = validatePassword(password)
        if (!validation.isValid) {
            throw IllegalArgumentException(validation.error)
        }

        // Combine password and UIN
        val input = "$password:$uin"
        val salt = "irm-key/$uin".toByteArray(Charsets.UTF_8)

        // Derive 32 bytes using PBKDF2
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(input.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val privBytes = factory.generateSecret(spec).encoded

        // Get P-256 curve parameters
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val ecParams = params.getParameterSpec(java.security.spec.ECParameterSpec::class.java)

        // Ensure private key is in valid range [1, n-1]
        var s = BigInteger(1, privBytes)
        val n = ecParams.order
        s = s.mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE)

        // Create private key
        val privKeySpec = ECPrivateKeySpec(s, ecParams)
        val kf = KeyFactory.getInstance("EC")
        val privKey = kf.generatePrivate(privKeySpec)

        // Derive public key: Q = d * G
        val g = ecParams.generator
        val q = scalarMultiply(g, s, ecParams)
        val pubKeySpec = ECPublicKeySpec(q, ecParams)
        val pubKey = kf.generatePublic(pubKeySpec)

        return KeyPair(pubKey, privKey)
    }

    /**
     * Derive fingerprint from password + UIN (for server verification without exposing password)
     */
    fun deriveFingerprint(password: String, uin: Long): String {
        val input = "$password:$uin"
        val salt = "irm-fp/$uin".toByteArray(Charsets.UTF_8)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(input.toCharArray(), salt, 50_000, 256)
        val fpBytes = factory.generateSecret(spec).encoded

        return fpBytes.joinToString("") { "%02x".format(it) }
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

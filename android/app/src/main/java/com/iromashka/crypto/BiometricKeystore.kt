package com.iromashka.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wrap/unwrap arbitrary bytes with an Android Keystore-backed AES/GCM key.
 * The key requires biometric authentication to use (setUserAuthenticationRequired = true).
 *
 * Used for G2 fast-unlock: server-issued unlock_token is wrapped here so that
 * tapping the fingerprint sensor produces a Cipher we can decrypt the token with.
 */
object BiometricKeystore {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "irm_biometric_unlock_v1"
    private const val GCM_TAG_BITS = 128

    /** Returns true if device has at least one biometric enrolled and is capable. */
    fun canAuthenticate(ctx: Context): Boolean {
        val bm = BiometricManager.from(ctx)
        val r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        return r == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(KS).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keystore()
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(spec)
        return kg.generateKey()
    }

    /** Drop the keystore key (used on logout). */
    fun deleteKey() {
        try { keystore().deleteEntry(ALIAS) } catch (_: Throwable) {}
    }

    /** Build a Cipher initialized for ENCRYPT, ready to be passed to BiometricPrompt. */
    fun buildEncryptCipher(): Cipher {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Build a Cipher initialized for DECRYPT with given IV (Base64 in storage). */
    fun buildDecryptCipher(ivBytes: ByteArray): Cipher {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
        return cipher
    }

    /**
     * Show BiometricPrompt to authenticate ENCRYPT operation, then wrap [plaintext].
     * Returns Pair(ciphertextBase64, ivBase64) via [onSuccess], or [onFail] with reason.
     */
    fun wrapWithBiometric(
        activity: FragmentActivity,
        plaintext: ByteArray,
        title: String,
        subtitle: String,
        onSuccess: (cipherB64: String, ivB64: String) -> Unit,
        onFail: (reason: String) -> Unit
    ) {
        val cipher = try { buildEncryptCipher() } catch (e: Throwable) {
            onFail(e.message ?: "keystore err"); return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Отмена")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        val prompt = BiometricPrompt(
            activity,
            { activity.runOnUiThread(it) } as java.util.concurrent.Executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return onFail("no cipher")
                    try {
                        val ct = c.doFinal(plaintext)
                        val iv = c.iv
                        onSuccess(android.util.Base64.encodeToString(ct, android.util.Base64.NO_WRAP),
                                  android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                    } catch (e: Throwable) { onFail(e.message ?: "encrypt err") }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { onFail("err $code: $msg") }
                override fun onAuthenticationFailed() { /* user can retry */ }
            }
        )
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Show BiometricPrompt to authenticate DECRYPT, then unwrap [ciphertext]. */
    fun unwrapWithBiometric(
        activity: FragmentActivity,
        ciphertextB64: String,
        ivB64: String,
        title: String,
        subtitle: String,
        onSuccess: (plaintext: ByteArray) -> Unit,
        onFail: (reason: String) -> Unit
    ) {
        val ct = try { android.util.Base64.decode(ciphertextB64, android.util.Base64.NO_WRAP) }
                 catch (e: Throwable) { return onFail("bad ct") }
        val iv = try { android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP) }
                 catch (e: Throwable) { return onFail("bad iv") }
        val cipher = try { buildDecryptCipher(iv) } catch (e: Throwable) {
            onFail(e.message ?: "keystore err"); return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Ввести пароль")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        val prompt = BiometricPrompt(
            activity,
            { activity.runOnUiThread(it) } as java.util.concurrent.Executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher ?: return onFail("no cipher")
                    try { onSuccess(c.doFinal(ct)) }
                    catch (e: Throwable) { onFail(e.message ?: "decrypt err") }
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { onFail("err $code: $msg") }
                override fun onAuthenticationFailed() {}
            }
        )
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }
}

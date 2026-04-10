package com.iromashka.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val NAME_SECURE = "iromashka_secure"
    private const val NAME_SIMPLE = "iromashka_pref"

    private fun securePrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, NAME_SECURE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun simplePrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME_SIMPLE, Context.MODE_PRIVATE)

    fun saveSession(ctx: Context, uin: Long, nickname: String, token: String,
                    refreshToken: String, wrappedPriv: String, pubKey: String, deviceId: String) {
        securePrefs(ctx).edit().apply {
            putLong("uin", uin)
            putString("nickname", nickname)
            putString("token", token)
            putString("refresh_token", refreshToken)
            putString("wrapped_priv", wrappedPriv)
            putString("pub_key", pubKey)
            putString("device_id", deviceId)
            apply()
        }
    }

    fun updateTokens(ctx: Context, token: String, refreshToken: String) {
        securePrefs(ctx).edit().apply {
            putString("token", token)
            putString("refresh_token", refreshToken)
            apply()
        }
    }

    fun updateToken(ctx: Context, token: String) {
        securePrefs(ctx).edit().putString("token", token).apply()
    }

    fun updateWrappedPriv(ctx: Context, wrappedPriv: String) {
        securePrefs(ctx).edit().putString("wrapped_priv", wrappedPriv).apply()
    }

    fun getUin(ctx: Context): Long = securePrefs(ctx).getLong("uin", -1L)
    fun getNickname(ctx: Context): String = securePrefs(ctx).getString("nickname", "") ?: ""
    fun getToken(ctx: Context): String = securePrefs(ctx).getString("token", "") ?: ""
    fun getWrappedPriv(ctx: Context): String = securePrefs(ctx).getString("wrapped_priv", "") ?: ""
    fun getPubKey(ctx: Context): String = securePrefs(ctx).getString("pub_key", "") ?: ""
    fun getRefreshToken(ctx: Context): String = securePrefs(ctx).getString("refresh_token", "") ?: ""
    fun getDeviceId(ctx: Context): String = securePrefs(ctx).getString("device_id", "") ?: ""

    fun isLoggedIn(ctx: Context): Boolean = getUin(ctx) > 0 && getToken(ctx).isNotEmpty()

    fun clear(ctx: Context) {
        securePrefs(ctx).edit().clear().apply()
    }

    // Theme
    fun getTheme(ctx: Context): String = simplePrefs(ctx).getString("theme", "ROSE") ?: "ROSE"
    fun setTheme(ctx: Context, theme: String) {
        simplePrefs(ctx).edit().putString("theme", theme).apply()
    }

    // PIN lockout
    fun getPinFailures(ctx: Context): Int = simplePrefs(ctx).getInt("pin_fails", 0)
    fun recordPinFailure(ctx: Context) {
        val fails = getPinFailures(ctx) + 1
        simplePrefs(ctx).edit()
            .putInt("pin_fails", fails)
            .putLong("pin_lockout_time", System.currentTimeMillis())
            .apply()
    }
    fun resetPinFailures(ctx: Context) {
        simplePrefs(ctx).edit()
            .putInt("pin_fails", 0)
            .putLong("pin_lockout_time", 0)
            .apply()
    }
    fun getPinLockoutTime(ctx: Context): Long = simplePrefs(ctx).getLong("pin_lockout_time", 0)

    fun getPinLockoutRemainingSecs(ctx: Context): Int {
        val fails = getPinFailures(ctx)
        if (fails < 5) return 0
        val lockoutDurationMs = getLockoutDurationMillis(fails)
        val lockoutTime = getPinLockoutTime(ctx)
        if (lockoutTime <= 0) return 0
        val now = System.currentTimeMillis()
        val remaining = lockoutTime + lockoutDurationMs - now
        return maxOf(0, (remaining / 1000).toInt())
    }

    private fun getLockoutDurationMillis(failures: Int): Long = when {
        failures < 10 -> 30_000L
        failures < 15 -> 60_000L
        failures < 20 -> 5 * 60_000L
        failures < 25 -> 15 * 60_000L
        failures < 30 -> 30 * 60_000L
        failures < 35 -> 60 * 60_000L
        failures < 40 -> 4 * 60 * 60_000L
        else -> 24 * 60 * 60_000L
    }

    const val MAX_PIN_FAILURES = 5
}

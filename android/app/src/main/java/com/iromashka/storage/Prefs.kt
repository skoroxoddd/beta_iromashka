package com.iromashka.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object Prefs {
    private const val NAME_SECURE = "icq20_secure"
    private const val NAME_SIMPLE = "icq20_pref"
    
    private fun securePrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            NAME_SECURE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    private fun simplePrefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME_SIMPLE, Context.MODE_PRIVATE)

    fun saveSession(ctx: Context, uin: Long, nickname: String, token: String,
                    wrappedPriv: String, pubKey: String, refreshToken: String = "") {
        securePrefs(ctx).edit().apply {
            putLong("uin", uin)
            putString("nickname", nickname)
            putString("token", token)
            putString("wrapped_priv", wrappedPriv)
            putString("pub_key", pubKey)
            if (refreshToken.isNotEmpty()) putString("refresh_token", refreshToken)
            putLong("token_ts", System.currentTimeMillis())
            apply()
        }
    }

    
    fun getRefreshToken(ctx: Context): String = securePrefs(ctx).getString("refresh_token", "") ?: ""
    fun updateRefreshToken(ctx: Context, token: String) {
        securePrefs(ctx).edit().putString("refresh_token", token).apply()
    }
    fun getTokenTimestamp(ctx: Context): Long = securePrefs(ctx).getLong("token_ts", 0L)
    fun updateTokenTimestamp(ctx: Context, ts: Long) {
        securePrefs(ctx).edit().putLong("token_ts", ts).apply()
    }

    fun updateToken(ctx: Context, token: String) {
        securePrefs(ctx).edit().putString("token", token).apply()
    }

    fun updateUin(ctx: Context, uin: Long) {
        securePrefs(ctx).edit().putLong("uin", uin).apply()
    }

    fun updateWrappedPriv(ctx: Context, wrappedPriv: String) {
        securePrefs(ctx).edit().putString("wrapped_priv", wrappedPriv).apply()
    }

    fun getUin(ctx: Context): Long = securePrefs(ctx).getLong("uin", -1L)
    fun getNickname(ctx: Context): String = securePrefs(ctx).getString("nickname", "") ?: ""
    fun getToken(ctx: Context): String = securePrefs(ctx).getString("token", "") ?: ""
    fun getWrappedPriv(ctx: Context): String = securePrefs(ctx).getString("wrapped_priv", "") ?: ""
    fun getPubKey(ctx: Context): String = securePrefs(ctx).getString("pub_key", "") ?: ""

    fun isLoggedIn(ctx: Context): Boolean = getUin(ctx) > 0 && getToken(ctx).isNotEmpty()

    fun clear(ctx: Context) {
        securePrefs(ctx).edit().clear().apply()
    }

    // Theme
    fun getTheme(ctx: Context): String = simplePrefs(ctx).getString("theme", "Iromashka") ?: "ICQ"
    fun setTheme(ctx: Context, theme: String) {
        simplePrefs(ctx).edit().putString("theme", theme).apply()
    }

    fun getDeviceId(ctx: Context): String {
        val prefs = simplePrefs(ctx)
        var id = prefs.getString("device_id", null)
        if (id.isNullOrEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    // PIN lockout tracking
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

    /** Remaining seconds until unlock, or 0 if not locked */
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

    private fun getLockoutDurationMillis(failures: Int): Long {
        return when {
            failures < 10 -> 30_000L           // 5-9 fails: 30 sec
            failures < 15 -> 60_000L           // 10-14: 1 min
            failures < 20 -> 5 * 60_000L       // 15-19: 5 min
            failures < 25 -> 15 * 60_000L      // 20-24: 15 min
            failures < 30 -> 30 * 60_000L      // 25-29: 30 min
            failures < 35 -> 60 * 60_000L      // 30-34: 1 hour
            failures < 40 -> 4 * 60 * 60_000L  // 35-39: 4 hours
            else -> 24 * 60 * 60_000L          // 40+: 24 hours (wiped next step)
        }
    }

    /** Max failures before wiping keys */
    const val MAX_PIN_FAILURES = 50
}

package com.iromashka.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.ApiService
import com.iromashka.storage.Prefs

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val uin: Long) : AuthState()
    data class NeedsPasswordMigration(val uin: Long, val token: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

sealed class PaymentState {
    object Idle : PaymentState()
    object Loading : PaymentState()
    data class Created(val confirmationUrl: String, val phone: String) : PaymentState()
    data class Paid(val uin: Long?) : PaymentState()
    data class Error(val message: String) : PaymentState()
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiService.api
    private val ctx get() = getApplication<Application>()

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state

    private val _paymentState = MutableStateFlow<PaymentState>(PaymentState.Idle)
    val paymentState: StateFlow<PaymentState> = _paymentState

    fun createPayment(phone: String) {
        viewModelScope.launch {
            _paymentState.value = PaymentState.Loading
            runCatching {
                val clean = phone.filter { it.isDigit() }
                if (clean.length < 7) throw IllegalArgumentException("Неверный номер")
                val resp = api.createPayment(PaymentCreateRequest(phone = clean, amount = "100.00"))
                _paymentState.value = PaymentState.Created(resp.confirmation_url, clean)
            }.onFailure {
                val msg = when {
                    it.message?.contains("409") == true -> "Номер уже оплачен"
                    it.message?.contains("429") == true -> "Слишком много попыток, подождите"
                    else -> "Не удалось создать платёж"
                }
                _paymentState.value = PaymentState.Error(msg)
            }
        }
    }

    fun pollPaymentStatus(phone: String) {
        viewModelScope.launch {
            val clean = phone.filter { it.isDigit() }
            repeat(60) {
                kotlinx.coroutines.delay(2000)
                val resp = runCatching { api.paymentStatus(clean) }.getOrNull()
                if (resp?.paid == true) {
                    _paymentState.value = PaymentState.Paid(resp.uin)
                    return@launch
                }
            }
        }
    }

    fun resetPayment() {
        _paymentState.value = PaymentState.Idle
    }

    suspend fun lookupUinByPhone(phone: String): Long? {
        val clean = phone.filter { it.isDigit() }
        if (clean.length < 7) return null
        val resp = runCatching { api.paymentStatus(clean) }.getOrNull()
        return resp?.uin?.takeIf { it > 0 }
    }

    fun register(nickname: String, pin: String, password: String, phone: String = "70000000000", expectedUin: Long = 0L) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val kp = CryptoManager.generateKeyPair()
                val pubB64 = CryptoManager.exportPublicKey(kp.public)
                val wrappedPriv = CryptoManager.wrapPrivateKey(kp.private, pin)

                val resp = api.register(RegisterRequest(phone, pin, password, pubB64))

                // Sanity-check: UIN, который сервер вернул, должен совпасть с тем, что
                // показали юзеру на uin_reveal. Если разный — что-то протекло (race,
                // подмена телефона, баг сервера); прерываем чтобы юзер не получил
                // чужой аккаунт.
                if (expectedUin > 0 && resp.uin != expectedUin) {
                    android.util.Log.e("AuthVM", "Register UIN mismatch: expected=$expectedUin server=${resp.uin}")
                    throw IllegalStateException("uin_mismatch")
                }

                Prefs.saveSession(ctx, resp.uin, nickname, resp.token, wrappedPriv, pubB64, resp.refresh_token)
                registerDevice(resp.token, pubB64)

                // Save wrapped private key to server for cross-device recovery
                runCatching {
                    api.saveUserKey("Bearer ${resp.token}", com.iromashka.network.SaveKeyRequest(
                        encrypted_key = wrappedPriv,
                        salt = ""
                    ))
                }

                _state.value = AuthState.Success(resp.uin)
            }.onFailure {
                val raw = it.message.orEmpty()
                val msg = when {
                    raw.contains("weak_password") -> "Слабый пароль: мин. 12 символов, 1 заглавная, 1 цифра, 1 спецсимвол"
                    raw.contains("phone_already_registered") || raw.contains("409") ->
                        "Этот номер уже зарегистрирован. Войдите по UIN+PIN или оплатите ещё одну активацию для нового UIN на этом номере."
                    raw.contains("payment_required") || raw.contains("402") ->
                        "Оплата не найдена. Оплатите активацию аккаунта."
                    raw.contains("uin_mismatch") ->
                        "Расхождение UIN. Перезапустите регистрацию."
                    else -> raw.ifEmpty { "Registration failed" }
                }
                _state.value = AuthState.Error(msg)
            }
        }
    }

    fun login(uin: Long, pin: String, password: String? = null) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val trimmedPassword = password?.trim()?.ifEmpty { null }

                // Раньше тут был early-return с verifyPin(local, pin) → "Неверный PIN-код".
                // Это давало ложный отказ, если локальный wrappedPriv устарел (другой PIN был
                // при его создании), но серверный канон с текущим PIN распаковывается ок.
                // Проверку PIN делаем по серверному ключу внутри syncIdentityFromServer ниже.

                val pubKey = Prefs.getPubKey(ctx)
                val resp = api.login(LoginRequest(uin, pin, trimmedPassword, pubKey.ifEmpty { null }))

                // Check if needs password migration
                if (resp.needs_password_migration) {
                    Prefs.updateToken(ctx, resp.token)
                    Prefs.updateRefreshToken(ctx, resp.refresh_token)
                    Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                    Prefs.updateUin(ctx, resp.uin)
                    android.util.Log.i("AuthVM", "Login OK (needs_password_migration) UIN $uin")
                    _state.value = AuthState.NeedsPasswordMigration(resp.uin, resp.token)
                    return@launch
                }

                Prefs.updateToken(ctx, resp.token)
                Prefs.updateRefreshToken(ctx, resp.refresh_token)
                Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                Prefs.updateUin(ctx, resp.uin)

                // ── Identity sync: на КАЖДОМ login сравнить локальный pubkey с серверным
                // и при расхождении взять серверный encrypted_key как канон. Иначе APK
                // может застрять со своим устаревшим privkey, пока PWA шифрует под
                // более свежим pubkey UIN — отсюда "не удалось расшифровать" PWA→APK.
                val syncOk = syncIdentityFromServer(uin, pin, resp.token, resp.encrypted_key)
                if (!syncOk) {
                    _state.value = AuthState.Error("Неверный PIN-код")
                    return@launch
                }

                registerDevice(resp.token, Prefs.getPubKey(ctx))
                requestUnlockToken(resp.token)
                _state.value = AuthState.Success(resp.uin)
            }.onFailure { e ->
                android.util.Log.w("AuthVM", "Login failed UIN $uin: ${e.message}", e)
                val msg = when {
                    e.message?.contains("401") == true -> "Неверный UIN, PIN или пароль"
                    e.message?.contains("403") == true -> "Аккаунт заблокирован"
                    e.message?.contains("429") == true -> "Слишком много попыток, подождите"
                    else -> e.message ?: "Login failed"
                }
                _state.value = AuthState.Error(msg)
            }
        }
    }

    fun changePin(oldPin: String, newPin: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = Prefs.getToken(ctx)
                val wrappedPriv = Prefs.getWrappedPriv(ctx)
                val newWrapped = CryptoManager.rewrapPrivateKey(wrappedPriv, oldPin, newPin)

                val resp = api.changePin("Bearer $token", ChangePinRequest(oldPin, newPin))
                Prefs.updateToken(ctx, resp.token)
                Prefs.updateWrappedPriv(ctx, newWrapped)
                onSuccess()
            }.onFailure {
                onError(it.message ?: "Не удалось сменить PIN")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // best-effort revoke server-side unlock_token before wipe
            runCatching {
                val token = Prefs.getToken(ctx)
                val deviceId = Prefs.getDeviceId(ctx)
                if (token.isNotEmpty()) {
                    api.unlockRevoke("Bearer $token",
                        com.iromashka.network.UnlockRevokeRequest(device_id = deviceId))
                }
            }
            com.iromashka.crypto.BiometricKeystore.deleteKey()
            Prefs.clearUnlockToken(ctx)
            Prefs.setBiometricEnabled(ctx, false)
            Prefs.clear(ctx)
            _state.value = AuthState.Idle
        }
    }

    /** G2: ask server for a 7-day unlock_token. Stored in plain Prefs until biometric opt-in. */
    private suspend fun requestUnlockToken(jwt: String) {
        runCatching {
            val deviceId = Prefs.getDeviceId(ctx)
            val resp = api.unlockIssue("Bearer $jwt",
                com.iromashka.network.UnlockIssueRequest(device_id = deviceId))
            // Stash plaintext temporarily — UI layer prompts user to enable biometric and
            // re-saves it Keystore-wrapped via BiometricKeystore.wrapWithBiometric().
            // We record only expires_at here; the token itself goes through the UI flow.
            Prefs.setUnlockToken(ctx,
                wrapped = "PLAIN:" + resp.unlock_token,
                iv = "",
                expiresAt = resp.expires_at)
            android.util.Log.i("AuthVM", "Unlock token issued, expires_at=${resp.expires_at}")
        }.onFailure {
            android.util.Log.w("AuthVM", "Unlock token issue failed: ${it.message}")
        }
    }

    /**
     * Try to fast-unlock using saved unlock_token (after biometric, if enabled, or plain if not).
     * Returns true on success (Prefs updated with new JWT), false otherwise.
     */
    suspend fun tryFastUnlockWithToken(unlockToken: String): Boolean {
        val uin = Prefs.getUin(ctx)
        if (uin <= 0) return false
        val deviceId = Prefs.getDeviceId(ctx)
        return runCatching {
            val resp = api.unlockVerify(com.iromashka.network.UnlockVerifyRequest(
                uin = uin, device_id = deviceId, unlock_token = unlockToken
            ))
            Prefs.updateToken(ctx, resp.token)
            Prefs.updateRefreshToken(ctx, resp.refresh_token)
            Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
            true
        }.getOrElse {
            android.util.Log.w("AuthVM", "Fast unlock verify failed: ${it.message}")
            // 401 → drop saved token, force fresh login
            if (it.message?.contains("401") == true || it.message?.contains("403") == true) {
                Prefs.clearUnlockToken(ctx)
                com.iromashka.crypto.BiometricKeystore.deleteKey()
                Prefs.setBiometricEnabled(ctx, false)
            }
            false
        }
    }

    fun resetState() {
        _state.value = AuthState.Idle
    }

    fun setPassword(password: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = Prefs.getToken(ctx)
                api.setPassword("Bearer $token", SetPasswordRequest(password))
                onSuccess()
            }.onFailure {
                val msg = when {
                    it.message?.contains("weak_password") == true -> "Слабый пароль"
                    it.message?.contains("already_set") == true -> "Пароль уже установлен"
                    else -> it.message ?: "Не удалось установить пароль"
                }
                onError(msg)
            }
        }
    }

    /**
     * Sync identity keypair с сервером.
     *
     * Возвращает true если в Prefs лежит правильный (соответствующий серверу) wrappedPriv+pubKey,
     * false если PIN не подходит к серверному ключу.
     *
     * Логика:
     *  1) если сервер ничего не хранит (encrypted_key пуст) — поведение как было: генерим, пушим.
     *  2) если сервер хранит ключ — он канонический. Сверяем с локальным:
     *     - Если локального нет ИЛИ локальный != серверному → распаковываем серверный текущим PIN.
     *       Если PIN подходит — заменяем локальный, тянем pubkey из API.
     *       Если не подходит — return false (purge не делаем, чтобы пользователь не потерял
     *       историю при простой опечатке в PIN).
     *     - Если локальный == серверный — ничего делать не нужно.
     */
    private suspend fun syncIdentityFromServer(
        uin: Long,
        pin: String,
        token: String,
        serverWrapped: String?
    ): Boolean {
        val localWrapped = Prefs.getWrappedPriv(ctx)

        // Случай 1: сервер пуст → первое устройство, генерим ключ если ещё нет.
        if (serverWrapped.isNullOrEmpty()) {
            if (localWrapped.isEmpty()) {
                val kp = CryptoManager.generateKeyPair()
                val newWrapped = CryptoManager.wrapPrivateKey(kp.private, pin)
                val newPub = CryptoManager.exportPublicKey(kp.public)
                Prefs.updateWrappedPriv(ctx, newWrapped)
                Prefs.updatePubKey(ctx, newPub)
                runCatching {
                    api.updatePubkey("Bearer $token",
                        com.iromashka.network.UpdatePubkeyRequest(newPub))
                }
                runCatching {
                    api.saveUserKey("Bearer $token",
                        com.iromashka.network.SaveKeyRequest(encrypted_key = newWrapped, salt = ""))
                }
                android.util.Log.w("AuthVM", "No server key for UIN $uin — generated fresh keypair")
            }
            return true
        }

        // Случай 2: сервер хранит ключ. Если локальный совпадает — всё хорошо.
        if (localWrapped == serverWrapped) {
            // Доп. страховка: pubkey в Prefs мог отстать (например, после change-pin без push).
            runCatching {
                val pk = api.getPubKey("Bearer $token", uin)
                if (pk.pubkey.isNotEmpty() && pk.pubkey != Prefs.getPubKey(ctx)) {
                    Prefs.updatePubKey(ctx, pk.pubkey)
                }
            }
            return true
        }

        // Случай 3: серверный != локальный (или локальный пуст). Серверный — канон.
        // Распаковываем серверный текущим PIN. Если ок — заменяем.
        val serverUnwraps = runCatching { CryptoManager.unwrapPrivateKey(serverWrapped, pin) }.isSuccess
        if (!serverUnwraps) {
            android.util.Log.w("AuthVM", "syncIdentity: server key for UIN $uin does not unwrap with given PIN")
            return false
        }

        Prefs.updateWrappedPriv(ctx, serverWrapped)
        runCatching {
            val pk = api.getPubKey("Bearer $token", uin)
            if (pk.pubkey.isNotEmpty()) Prefs.updatePubKey(ctx, pk.pubkey)
        }
        android.util.Log.i("AuthVM", "syncIdentity: replaced local identity with server canon for UIN $uin (was ${if (localWrapped.isEmpty()) "empty" else "stale"})")
        return true
    }

    private suspend fun registerDevice(token: String, pubKey: String) {
        if (pubKey.isEmpty()) return
        runCatching {
            val deviceId = Prefs.getDeviceId(ctx)
            api.registerDevice(
                "Bearer $token",
                com.iromashka.network.RegisterDeviceRequest(
                    device_id = deviceId,
                    pubkey = pubKey,
                    device_name = "Android"
                )
            )
        }
    }
}

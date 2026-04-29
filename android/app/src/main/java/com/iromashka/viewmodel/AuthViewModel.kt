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

    fun register(nickname: String, pin: String, password: String, phone: String = "70000000000") {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val kp = CryptoManager.generateKeyPair()
                val pubB64 = CryptoManager.exportPublicKey(kp.public)
                val wrappedPriv = CryptoManager.wrapPrivateKey(kp.private, pin)

                val resp = api.register(RegisterRequest(phone, pin, password, pubB64))

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
                val msg = when {
                    it.message?.contains("weak_password") == true -> "Слабый пароль: мин. 12 символов, 1 заглавная, 1 цифра, 1 спецсимвол"
                    else -> it.message ?: "Registration failed"
                }
                _state.value = AuthState.Error(msg)
            }
        }
    }

    fun login(uin: Long, pin: String, password: String? = null) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val wrappedPriv = Prefs.getWrappedPriv(ctx)

                // Local key exists — verify PIN before hitting server
                if (wrappedPriv.isNotEmpty()) {
                    if (!CryptoManager.verifyPin(wrappedPriv, pin)) {
                        _state.value = AuthState.Error("Неверный PIN-код")
                        return@launch
                    }
                }

                val pubKey = Prefs.getPubKey(ctx)
                val resp = api.login(LoginRequest(uin, pin, password, pubKey.ifEmpty { null }))

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

                // First-time login on this device (no local wrapped key)
                if (wrappedPriv.isEmpty()) {
                    val serverKey = resp.encrypted_key
                    var restoredKey: String? = null
                    if (!serverKey.isNullOrEmpty()) {
                        // Strict unwrap: server holds the canonical identity key
                        val ok = runCatching { CryptoManager.unwrapPrivateKey(serverKey, pin) }.isSuccess
                        if (ok) restoredKey = serverKey
                        else android.util.Log.w("AuthVM", "Server has encrypted_key for UIN $uin but PIN does not unwrap it")
                    }

                    if (restoredKey != null) {
                        Prefs.updateWrappedPriv(ctx, restoredKey)
                        // Re-derive our pubkey from server-stored identity, fetch via API
                        runCatching {
                            val pk = api.getPubKey(uin)
                            if (pk.pubkey.isNotEmpty()) Prefs.updatePubKey(ctx, pk.pubkey)
                        }
                        android.util.Log.i("AuthVM", "Restored shared identity key from server for UIN $uin")
                    } else if (serverKey.isNullOrEmpty()) {
                        // Server has nothing at all — first device for this account, generate + push
                        val kp = CryptoManager.generateKeyPair()
                        val newWrapped = CryptoManager.wrapPrivateKey(kp.private, pin)
                        val newPub = CryptoManager.exportPublicKey(kp.public)
                        Prefs.updateWrappedPriv(ctx, newWrapped)
                        Prefs.updatePubKey(ctx, newPub)
                        runCatching {
                            api.updatePubkey("Bearer ${resp.token}",
                                com.iromashka.network.UpdatePubkeyRequest(newPub))
                        }
                        runCatching {
                            api.saveUserKey("Bearer ${resp.token}",
                                com.iromashka.network.SaveKeyRequest(encrypted_key = newWrapped, salt = ""))
                        }
                        android.util.Log.w("AuthVM", "No server key for UIN $uin — generated fresh keypair")
                    } else {
                        // Server has a key but PIN does not unwrap it → wrong PIN
                        _state.value = AuthState.Error("Неверный PIN-код")
                        return@launch
                    }
                }

                registerDevice(resp.token, Prefs.getPubKey(ctx))
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
        Prefs.clear(ctx)
        _state.value = AuthState.Idle
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

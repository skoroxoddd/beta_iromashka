package com.iromashka.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.ApiService
import com.iromashka.storage.*
import java.util.UUID

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    data class Success(val uin: Long) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiService.api
    private val ctx get() = getApplication<Application>()

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var pubKeyB64: String = ""
    private var wrappedPriv: String = ""

    private fun getOrCreateDeviceId(): String {
        val existing = Prefs.getDeviceId(ctx)
        if (existing.isNotEmpty()) return existing
        return UUID.randomUUID().toString()
    }

    fun register(pin: String, phone: String) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val kp = CryptoManager.generateKeyPair()
                pubKeyB64 = CryptoManager.exportPublicKey(kp.public)
                wrappedPriv = CryptoManager.wrapPrivateKey(kp.private, pin)
                val deviceId = getOrCreateDeviceId()

                val resp = api.register(RegisterRequest(phone, pin, pubKeyB64, deviceId, wrappedPriv))

                AppDatabase.getInstance(ctx).messageDao().deleteAll()
                AppDatabase.getInstance(ctx).groupMessageDao().clearAllGroups()

                Prefs.saveSession(
                    ctx,
                    uin = resp.uin,
                    nickname = "",
                    token = resp.token,
                    refreshToken = resp.refresh_token,
                    wrappedPriv = wrappedPriv,
                    pubKey = pubKeyB64,
                    deviceId = deviceId
                )

                ApiService.setTokenProvider { Prefs.getToken(ctx) }
                _state.value = AuthState.Success(resp.uin)
            }.onFailure {
                _state.value = AuthState.Error(it.message ?: "Registration failed")
            }
        }
    }

    fun login(uin: Long, pin: String) {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val savedWrapped = Prefs.getWrappedPriv(ctx)
                if (savedWrapped.isNotEmpty() && !CryptoManager.verifyPin(savedWrapped, pin)) {
                    _state.value = AuthState.Error("Неверный PIN-код")
                    return@launch
                }

                val resp = api.login(LoginRequest(uin, pin))

                var finalWrapped = savedWrapped
                var finalPubKey = Prefs.getPubKey(ctx)

                if (savedWrapped.isEmpty()) {
                    val kp = CryptoManager.generateKeyPair()
                    finalWrapped = CryptoManager.wrapPrivateKey(kp.private, pin)
                    finalPubKey = CryptoManager.exportPublicKey(kp.public)
                    runCatching {
                        api.updatePubKey("Bearer ${resp.token}", UpdatePubKeyRequest(finalPubKey))
                    }
                }

                val deviceId = getOrCreateDeviceId()
                Prefs.saveSession(
                    ctx,
                    uin = resp.uin,
                    nickname = "",
                    token = resp.token,
                    refreshToken = resp.refresh_token,
                    wrappedPriv = finalWrapped,
                    pubKey = finalPubKey,
                    deviceId = deviceId
                )

                ApiService.setTokenProvider { Prefs.getToken(ctx) }
                _state.value = AuthState.Success(resp.uin)
            }.onFailure {
                _state.value = AuthState.Error(it.message ?: "Login failed")
            }
        }
    }

    suspend fun refreshToken(): Boolean {
        val refreshTok = Prefs.getRefreshToken(ctx)
        if (refreshTok.isEmpty()) return false
        return runCatching {
            val resp = api.refresh(RefreshRequest(refreshTok))
            Prefs.updateTokens(ctx, resp.token, resp.refresh_token)
            true
        }.getOrElse { false }
    }

    fun changePin(oldPin: String, newPin: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val token = Prefs.getToken(ctx)
                val wrapped = Prefs.getWrappedPriv(ctx)
                val uin = Prefs.getUin(ctx)
                val newWrapped = CryptoManager.rewrapPrivateKey(wrapped, oldPin, newPin)

                api.changePin("Bearer $token", ChangePinRequest(uin, oldPin, newPin))
                Prefs.updateWrappedPriv(ctx, newWrapped)

                AppDatabase.getInstance(ctx).messageDao().deleteAll()
                AppDatabase.getInstance(ctx).groupMessageDao().clearAllGroups()

                onSuccess()
            }.onFailure {
                onError(it.message ?: "Не удалось сменить PIN")
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            runCatching {
                val token = Prefs.getToken(ctx)
                api.logout("Bearer $token")
            }
            Prefs.clear(ctx)
            ApiService.setTokenProvider { null }
            _state.value = AuthState.Idle
        }
    }

    fun resetState() {
        _state.value = AuthState.Idle
    }
}

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
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private val api = ApiService.api
    private val ctx get() = getApplication<Application>()

    private val _state = MutableStateFlow<AuthState>(AuthState.Idle)
    val state: StateFlow<AuthState> = _state

    fun register(nickname: String, pin: String, phone: String = "70000000000") {
        viewModelScope.launch {
            _state.value = AuthState.Loading
            runCatching {
                val kp = CryptoManager.generateKeyPair()
                val pubB64 = CryptoManager.exportPublicKey(kp.public)
                val wrappedPriv = CryptoManager.wrapPrivateKey(kp.private, pin)

                val resp = api.register(RegisterRequest(phone, pin, pubB64))

                Prefs.saveSession(ctx, resp.uin, nickname, resp.token, wrappedPriv, pubB64, resp.refresh_token)
                registerDevice(resp.token, pubB64)
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
                val wrappedPriv = Prefs.getWrappedPriv(ctx)
                if (wrappedPriv.isNotEmpty() && !CryptoManager.verifyPin(wrappedPriv, pin)) {
                    _state.value = AuthState.Error("Неверный PIN-код")
                    return@launch
                }

                val resp = api.login(LoginRequest(uin, pin))
                Prefs.updateToken(ctx, resp.token)
                Prefs.updateRefreshToken(ctx, resp.refresh_token)
                Prefs.updateTokenTimestamp(ctx, System.currentTimeMillis())
                Prefs.updateUin(ctx, resp.uin)
                registerDevice(resp.token, Prefs.getPubKey(ctx))
                _state.value = AuthState.Success(resp.uin)
            }.onFailure {
                _state.value = AuthState.Error(it.message ?: "Login failed")
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

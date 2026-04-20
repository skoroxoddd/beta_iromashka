package com.iromashka.model

import com.google.gson.annotations.SerializedName

// --- REST API models ---

data class RegisterRequest(
    @SerializedName("phone") val phone: String,
    val pin: String,
    val public_key: String
)

data class RegisterResponse(
    val uin: Long,
    val token: String,
    val refresh_token: String = ""
)

data class LoginRequest(
    val uin: Long,
    val pin: String
)

data class LoginResponse(
    val token: String,
    val uin: Long,
    val refresh_token: String = ""
)

data class ChangePinRequest(
    val old_pin: String,
    val new_pin: String
)

data class ChangePinResponse(
    val token: String
)

data class PubKeyResponse(
    @SerializedName("pubkey") val pubkey: String
)

data class FcmTokenRequest(
    val fcm_token: String
)

// --- WebSocket models ---

data class WsAuthPacket(
    val uin: Long,
    val token: String
)

data class WsEnvelope(
    val sender_uin: Long,
    val receiver_uin: Long,
    val ciphertext: String,
    val timestamp: Long
)

// --- Internal app models ---

data class Contact(
    val uin: Long,
    val nickname: String,
    val isOnline: Boolean = false,
    val unread: Int = 0
)


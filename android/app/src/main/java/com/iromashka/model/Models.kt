package com.iromashka.model

import com.google.gson.annotations.SerializedName

// --- REST API models ---

data class RegisterRequest(
    @SerializedName("phone") val phone: String,
    val pin: String,
    val password: String,
    val public_key: String
)

data class RegisterResponse(
    val uin: Long,
    val token: String,
    val refresh_token: String = ""
)

data class LoginRequest(
    val uin: Long,
    val pin: String,
    val password: String? = null,
    val public_key: String? = null
)

data class LoginResponse(
    val token: String,
    val uin: Long,
    val refresh_token: String = "",
    val encrypted_key: String? = null,
    val key_salt: String? = null,
    val new_session: Boolean = false,
    val needs_password_migration: Boolean = false
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


data class PaymentCreateRequest(
    val phone: String,
    val amount: String? = "100.00"
)

data class PaymentCreateResponse(
    val payment_id: String,
    val confirmation_url: String
)

data class PaymentStatusResponse(
    val paid: Boolean,
    val uin: Long? = null
)

// Password migration
data class SetPasswordRequest(
    val password: String
)

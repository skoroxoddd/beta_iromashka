package com.iromashka.model

import com.google.gson.annotations.SerializedName

// ── REST API ──

data class RegisterRequest(
    @SerializedName("phone") val phone: String,
    val pin: String,
    val public_key: String,
    val device_id: String? = null
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

data class RefreshRequest(val refresh_token: String)

data class RefreshResponse(
    val token: String,
    val refresh_token: String
)

data class ChangePinRequest(
    val uin: Long,
    val old_pin: String,
    val new_pin: String
)

data class PubKeyResponse(
    val uin: Long = 0,
    @SerializedName("pubkey") val pubkey: String = ""
)

data class UpdatePubKeyRequest(
    @SerializedName("pubkey") val pubkey: String
)

// ── Contacts ──

data class DiscoverRequest(val phones: List<String>)

data class DiscoveredContactItem(
    val uin: Long,
    val phone: String,
    val nickname: String
)

data class PhoneLookupResult(
    val phone: String,
    val contactName: String,
    val uin: Long? = null,
    val nickname: String? = null
)

// ── Groups ──

data class CreateGroupRequest(val name: String, val member_uins: List<Long>)
data class GroupResponse(val id: Long, val name: String, val member_count: Int)
data class GroupItem(val id: Long, val name: String)
data class GroupMemberItem(val uin: Long, val nickname: String)
data class AddMemberBody(val uin: Long)

// ── WebSocket ──

data class WsAuthPacket(val uin: Long, val token: String)

data class WsAuthOk(
    val sys: String,
    val uin: Long,
    val sk: String? = null  // session key for ChaCha20 v3
)

data class WsSystemMsg(val sys: String)

data class WsEnvelope(
    val sender_uin: Long = 0,
    val receiver_uin: Long = 0,
    val ciphertext: String = "",
    val timestamp: Long = 0
)

// ── Typed WS envelope ──

data class WsTypedEnvelope(
    val type: String = "",
    val data: WsData? = null
)

sealed class WsData

data class WsMessageData(
    val sender_uin: Long = 0,
    val receiver_uin: Long = 0,
    val ciphertext: String = "",
    val timestamp: Long = 0
) : WsData()

data class WsTypingData(
    val sender_uin: Long = 0,
    val receiver_uin: Long = 0,
    val is_typing: Boolean = false
) : WsData()

data class WsReadReceiptData(
    val sender_uin: Long = 0,
    val receiver_uin: Long = 0,
    val read_until: Long = 0
) : WsData()

data class WsMsgAckData(
    val sender_uin: Long = 0,
    val receiver_uin: Long = 0,
    val msg_time: Long = 0,
    val status: String = ""
) : WsData()

data class WsChangeStatusData(val status: String = "") : WsData()

// ── Internal ──

data class Contact(
    val uin: Long,
    val nickname: String,
    val phone: String = "",
    val isOnline: Boolean = false,
    val unread: Int = 0,
    val lastMessage: String = "",
    val lastMessageTime: Long = 0
)

data class E2EPayload(
    val v: Int = 1,
    val epk: String = "",
    val iv: String = "",
    val ct: String = ""
)

// ── HTTP Poll/Send ──

data class PollResponse(val messages: List<WsEnvelope>)

data class SendRequest(
    val receiver_uin: Long,
    val ciphertext: String
)

data class SendResponse(val status: String)

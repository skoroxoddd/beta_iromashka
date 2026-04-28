package com.iromashka.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import com.google.gson.Gson
import com.iromashka.model.*

object ApiService {
    const val WS_URL = "wss://iromashka.ru/chat"
    private const val BASE_URL = "https://iromashka.ru/api/"

    interface Api {
        @POST("register")
        suspend fun register(@Body body: RegisterRequest): RegisterResponse

        @POST("refresh")
        suspend fun refresh(@Header("Authorization") token: String, @Body body: RefreshRequest): RefreshResponse

        @POST("login")
        suspend fun login(@Body body: LoginRequest): LoginResponse

        @GET("pubkey/{uin}")
        suspend fun getPubKey(@Path("uin") uin: Long): PubKeyResponse

        @PUT("fcm-token")
        suspend fun setFcmToken(@Header("Authorization") token: String, @Body body: FcmTokenRequest)

        @POST("create-group")
        suspend fun createGroup(@Header("Authorization") token: String, @Body body: CreateGroupRequest): GroupResponse

        @GET("groups")
        suspend fun listGroups(@Header("Authorization") token: String): List<GroupItem>

        @GET("group/{id}/members")
        suspend fun getGroupMembers(@Header("Authorization") token: String, @Path("id") groupId: Long): List<GroupMemberItem>

        @POST("group/{id}/members")
        suspend fun addGroupMember(@Header("Authorization") token: String, @Path("id") groupId: Long, @Body body: AddMemberBody)

        @GET("group/{id}/key")
        suspend fun getGroupKey(@Header("Authorization") token: String, @Path("id") groupId: Long): GroupKeyResponse

        @POST("group/{id}/key")
        suspend fun setGroupKeys(@Header("Authorization") token: String, @Path("id") groupId: Long, @Body body: SetGroupKeysRequest): okhttp3.ResponseBody

        @POST("group/{id}/send")
        suspend fun sendGroupMessage(@Header("Authorization") token: String, @Path("id") groupId: Long, @Body body: GroupSendRequest): okhttp3.ResponseBody

        @POST("contacts/discover")
        suspend fun discoverContacts(@Header("Authorization") token: String, @Body body: DiscoverRequest): List<DiscoveredContactItem>

        @POST("change-pin")
        suspend fun changePin(@Header("Authorization") token: String, @Body body: ChangePinRequest): ChangePinResponse

        @POST("devices/register")
        suspend fun registerDevice(@Header("Authorization") token: String, @Body body: RegisterDeviceRequest)

        @retrofit2.http.DELETE("devices/{device_id}")
        suspend fun deleteDevice(@Header("Authorization") token: String, @Path("device_id") deviceId: String): okhttp3.ResponseBody

        @GET("user/{uin}/devices")
        suspend fun getUserDevices(@Header("Authorization") token: String, @Path("uin") uin: Long): List<DeviceInfoResponse>

        @GET("messages/sync")
        suspend fun getSyncedMessages(
            @Header("Authorization") token: String,
            @Query("since") since: Long = 0,
            @Query("limit") limit: Int = 500
        ): List<SyncedMessageItem>

        @POST("messages/sync")
        suspend fun saveSyncedMessage(
            @Header("Authorization") token: String,
            @Body body: SaveSyncedMessageRequest
        ): okhttp3.ResponseBody

        @POST("save-key")
        suspend fun saveUserKey(
            @Header("Authorization") token: String,
            @Body body: SaveKeyRequest
        ): okhttp3.ResponseBody

        @POST("identity/reset")
        suspend fun identityReset(
            @Header("Authorization") token: String,
            @Body body: IdentityResetRequest
        ): okhttp3.ResponseBody

        @POST("update-pubkey")
        suspend fun updatePubkey(
            @Header("Authorization") token: String,
            @Body body: UpdatePubkeyRequest
        ): okhttp3.ResponseBody

        @POST("recovery/save")
        suspend fun recoverySave(
            @Header("Authorization") token: String,
            @Body body: RecoverySaveRequest
        ): okhttp3.ResponseBody

        @POST("recovery/lookup")
        suspend fun recoveryLookup(@Body body: RecoveryLookupRequest): RecoveryLookupResponse

        @POST("recovery/init")
        suspend fun recoveryInit(@Body body: RecoveryInitRequest): RecoveryInitResponse

        @POST("recovery/complete")
        suspend fun recoveryComplete(@Body body: RecoveryCompleteRequest): LoginResponse

        @POST("set-password")
        suspend fun setPassword(@Header("Authorization") token: String, @Body body: SetPasswordRequest): okhttp3.ResponseBody

        @POST("payment/create")
        suspend fun createPayment(@Body body: PaymentCreateRequest): PaymentCreateResponse

        @GET("payment/status")
        suspend fun paymentStatus(@retrofit2.http.Query("phone") phone: String): PaymentStatusResponse
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()

    val api: Api = retrofit.create(Api::class.java)
}

data class CreateGroupRequest(val name: String, val member_uins: List<Long>)
data class GroupResponse(val id: Long, val name: String, val member_count: Int)
data class GroupItem(val id: Long, val name: String)
data class GroupMemberItem(val uin: Long, val nickname: String)
data class AddMemberBody(val uin: Long)
data class GroupMessageRequest(val senderUin: Long = 0, val group_id: Long, val ciphertext: String)
data class GroupKeyResponse(val encrypted_key: String)
data class SetGroupKeysRequest(val keys: List<GroupKeyEntry>)
data class GroupKeyEntry(val uin: Long, val encrypted_key: String)
data class GroupSendRequest(val ciphertext: String)

data class DiscoverRequest(val phones: List<String>)
data class DiscoveredContactItem(val uin: Int, val phone: String, val nickname: String)

data class RegisterDeviceRequest(val device_id: String, val pubkey: String, val device_name: String)
data class DeviceInfoResponse(val device_id: String, val pubkey: String, val device_name: String, val last_seen: Long)

data class SyncedMessageItem(
    val sender_uin: Long,
    val receiver_uin: Long,
    val ciphertext: String,
    val timestamp: Long
)

data class SaveSyncedMessageRequest(
    val sender_uin: Long,
    val receiver_uin: Long,
    val ciphertext: String,
    val timestamp: Long
)

data class SaveKeyRequest(
    val encrypted_key: String,
    val salt: String
)

data class UpdatePubkeyRequest(val pubkey: String)

data class TypingPayload(val sender_uin: Long, val receiver_uin: Long, val is_typing: Boolean)

data class RefreshRequest(val refresh_token: String)
data class RefreshResponse(val token: String, val refresh_token: String)

data class IdentityResetRequest(val uin: Long, val pin: String)

data class RecoverySaveRequest(
    val wrapped_with_seed: String,
    val salt: String,
    val phrase_fingerprint: String
)

data class RecoveryLookupRequest(val phone: String)
data class RecoveryLookupResponse(val uin: Long?)

data class RecoveryInitRequest(
    val phone: String,
    val phrase_fingerprint: String
)

data class RecoveryInitResponse(
    val uin: Long,
    val wrapped_with_seed: String,
    val salt: String,
    val recovery_token: String
)

data class RecoveryCompleteRequest(
    val uin: Long,
    val recovery_token: String,
    val new_pin: String,
    val new_encrypted_key: String,
    val new_salt: String
)

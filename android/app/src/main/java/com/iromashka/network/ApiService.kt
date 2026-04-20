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
        suspend fun setFcmToken(
            @Header("Authorization") token: String,
            @Body body: FcmTokenRequest
        )

        // Groups
        @POST("create-group")
        suspend fun createGroup(
            @Header("Authorization") token: String,
            @Body body: CreateGroupRequest
        ): GroupResponse

        @GET("groups")
        suspend fun listGroups(
            @Header("Authorization") token: String
        ): List<GroupItem>

        @GET("group/{id}/members")
        suspend fun getGroupMembers(
            @Header("Authorization") token: String,
            @Path("id") groupId: Long
        ): List<GroupMemberItem>

        @POST("group/{id}/members")
        suspend fun addGroupMember(
            @Header("Authorization") token: String,
            @Path("id") groupId: Long,
            @Body body: AddMemberBody
        )

        // Contacts
        @POST("contacts/discover")
        suspend fun discoverContacts(
            @Header("Authorization") token: String,
            @Body body: DiscoverRequest
        ): List<DiscoveredContactItem>

        @POST("change-pin")
        suspend fun changePin(
            @Header("Authorization") token: String,
            @Body body: ChangePinRequest
        ): ChangePinResponse
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor())
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .client(client)
        .build()

    val api: Api = retrofit.create(Api::class.java)
}

// ── Data classes used only by API (not duplicated with Models.kt) ──

data class CreateGroupRequest(val name: String, val member_uins: List<Long>)
data class GroupResponse(val id: Long, val name: String, val member_count: Int)
data class GroupItem(val id: Long, val name: String)
data class GroupMemberItem(val uin: Long, val nickname: String)
data class AddMemberBody(val uin: Long)
data class GroupMessageRequest(val senderUin: Long = 0, val group_id: Long, val ciphertext: String)

data class DiscoverRequest(val phones: List<String>)
data class DiscoveredContactItem(val uin: Int, val phone: String, val nickname: String)

data class TypingPayload(val sender_uin: Long, val receiver_uin: Long, val is_typing: Boolean)
data class ReadReceiptPayload(val sender_uin: Long, val receiver_uin: Long, val read_until: Long)

// ── Refresh token ──
data class RefreshRequest(val refresh_token: String)
data class RefreshResponse(val token: String, val refresh_token: String)

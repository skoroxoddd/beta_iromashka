package com.iromashka.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import com.google.gson.Gson
import com.iromashka.model.*
import java.util.concurrent.TimeUnit

object ApiService {
    const val BASE = "https://iromashka.ru"
    const val WS_URL = "wss://iromashka.ru/chat"
    private const val BASE_URL = "$BASE/api/"

    interface Api {
        @POST("register")
        suspend fun register(@Body body: RegisterRequest): RegisterResponse

        @POST("login")
        suspend fun login(@Body body: LoginRequest): LoginResponse

        @POST("refresh")
        suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

        @POST("logout")
        suspend fun logout(@Header("Authorization") token: String)

        @GET("pubkey/{uin}")
        suspend fun getPubKey(@Path("uin") uin: Long): PubKeyResponse

        @POST("update-pubkey")
        suspend fun updatePubKey(
            @Header("Authorization") token: String,
            @Body body: UpdatePubKeyRequest
        )

        @POST("contacts/discover")
        suspend fun discoverContacts(
            @Header("Authorization") token: String,
            @Body body: DiscoverRequest
        ): List<DiscoveredContactItem>

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

        @POST("change-pin")
        suspend fun changePin(
            @Header("Authorization") token: String,
            @Body body: ChangePinRequest
        )

        @DELETE("account")
        suspend fun deleteAccount(@Header("Authorization") token: String)

        @GET("health")
        suspend fun health(): Map<String, Any>

        // HTTP Long-Polling fallback (v0.10.0)
        @POST("poll")
        suspend fun poll(@Header("Authorization") token: String): PollResponse

        @POST("send")
        suspend fun send(
            @Header("Authorization") token: String,
            @Body body: SendRequest
        ): SendResponse
    }

    class AuthInterceptor(
        private val tokenProvider: () -> String?
    ) : okhttp3.Interceptor {
        override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            if (original.header("Authorization") != null) return chain.proceed(original)
            val token = tokenProvider() ?: return chain.proceed(original)
            val request = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            return chain.proceed(request)
        }
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    @Volatile
    private var authInterceptor: AuthInterceptor? = null

    private val client: OkHttpClient
        get() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .also { builder ->
                authInterceptor?.let { builder.addInterceptor(it) }
            }
            .build()

    fun setTokenProvider(tokenProvider: () -> String?) {
        authInterceptor = AuthInterceptor(tokenProvider)
    }

    private val retrofit: Retrofit
        get() = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create(Gson()))
            .client(client)
            .build()

    val api: Api = retrofit.create(Api::class.java)
}

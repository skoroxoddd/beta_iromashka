package com.iromashka.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import com.iromashka.model.WsAuthPacket
import com.iromashka.model.WsEnvelope
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

sealed class WsEvent {
    object Connected : WsEvent()
    object Disconnected : WsEvent()
    data class MessageReceived(val envelope: WsEnvelope) : WsEvent()
    data class TypingReceived(val typing: com.iromashka.model.TypingEvent) : WsEvent()
    data class UserStatusReceived(val uin: Long, val status: String) : WsEvent()
    data class Error(val msg: String) : WsEvent()
    object AuthFailed : WsEvent()
}

/**
 * Transport obfuscation matching server protocol exactly.
 *
 * Frame v4: [magic 2B random][0x04][nonce 12B][ChaCha20(payload)][padding to 4KB]
 * Frame v2: [magic 2B random][0x02][xor_key 1B][len 4B LE][XOR(payload)][padding 0-64B]
 * Frame v1: [0x49 0x52][0x01][len 4B LE][payload][padding]
 *
 * Auth handshake:
 *   Client → Server: plain text JSON {"uin":..,"token":..}
 *   Server → Client: binary v2 frame containing {"sys":"auth_ok","uin":..,"sk":"<hex32>"}
 *   After auth_ok: all binary frames use v4 ChaCha20 with key = hex_decode(sk)
 */
private object Transport {

    // Decode incoming binary frame. Before auth_ok we only see v2.
    // After auth_ok we only see v4.
    fun decode(data: ByteArray, sessionKey: ByteArray?): ByteArray? {
        if (data.size < 3) return null
        return when (data[2]) {
            0x04.toByte() -> decodeV4(data, sessionKey ?: return null)
            0x03.toByte() -> decodeV4(data, sessionKey ?: return null) // v3 same structure
            0x02.toByte() -> decodeV2(data)
            0x01.toByte() -> decodeV1(data)
            else -> null
        }
    }

    private fun decodeV4(data: ByteArray, key: ByteArray): ByteArray? {
        // [2B magic][1B ver=0x04][12B nonce][ciphertext+padding]
        if (data.size < 15) return null
        val nonce = data.sliceArray(3..14)
        val ciphertext = data.sliceArray(15 until data.size)
        return chacha20Decrypt(key, nonce, ciphertext)
    }

    private fun decodeV2(data: ByteArray): ByteArray? {
        // [2B magic][1B ver=0x02][1B xor_key][4B len LE][XOR(payload)][padding]
        if (data.size < 8) return null
        val xorKey = data[3].toInt() and 0xFF
        val len = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > 65536 || data.size < 8 + len) return null
        val payload = data.sliceArray(8 until 8 + len)
        return ByteArray(len) { i -> (payload[i].toInt() xor ((xorKey + i) and 0xFF)).toByte() }
    }

    private fun decodeV1(data: ByteArray): ByteArray? {
        // [0x49 0x52][0x01][4B len LE][payload][padding]
        if (data.size < 7) return null
        val len = ByteBuffer.wrap(data, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > 65536 || data.size < 7 + len) return null
        return data.sliceArray(7 until 7 + len)
    }

    // Encode outgoing frame as v4 ChaCha20
    fun encodeV4(data: ByteArray, key: ByteArray): ByteArray {
        val nonce = Random.nextBytes(12)
        val ciphertext = chacha20Encrypt(key, nonce, data)
        val headerSize = 15
        val blockSize = 4096
        val total = headerSize + ciphertext.size
        val paddingLen = if (total < blockSize) blockSize - total
                         else (blockSize - (ciphertext.size % (blockSize - headerSize))) % (blockSize - headerSize)
        val out = ByteArray(headerSize + ciphertext.size + paddingLen)
        Random.nextBytes(2).copyInto(out, 0)
        out[2] = 0x04
        nonce.copyInto(out, 3)
        ciphertext.copyInto(out, 15)
        Random.nextBytes(paddingLen).copyInto(out, 15 + ciphertext.size)
        return out
    }

    // ChaCha20 using AES-CTR as approximation isn't good enough.
    // Android doesn't have native ChaCha20 before API 28.
    // We use Conscrypt/BouncyCastle via "ChaCha20" cipher name (API 28+) or fallback.
    private fun chacha20Decrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray? = runCatching {
        chacha20Crypt(key, nonce, data)
    }.getOrNull()

    private fun chacha20Encrypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray =
        chacha20Crypt(key, nonce, data)

    private fun chacha20Crypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            // API 28+: use system ChaCha20
            // javax.crypto ChaCha20 IV format: counter(4B LE) + nonce(12B) = 16B
            val iv = ByteArray(16)
            nonce.copyInto(iv, 4)
            val cipher = Cipher.getInstance("ChaCha20")
            val keySpec = SecretKeySpec(key, "ChaCha20")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(data)
        } else {
            // Fallback: pure-Kotlin ChaCha20
            ChaCha20.crypt(key, nonce, data)
        }
    }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }
}

class WsClient(
    private val uin: Long,
    private val token: String,
    private val scope: CoroutineScope
) {
    private val TAG = "WsClient"
    private val gson = Gson()

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 10

    // Session key received in auth_ok (hex-decoded)
    @Volatile private var sessionKey: ByteArray? = null

    private val client = okhttp3.OkHttpClient.Builder()
        .pingInterval(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        sessionKey = null
        val request = Request.Builder()
            .url(ApiService.WS_URL)
            .build()
        ws = client.newWebSocket(request, listener)
        Log.d(TAG, "Connecting to ${ApiService.WS_URL}")
    }

    fun disconnect() {
        reconnectJob?.cancel()
        ws?.close(1000, "logout")
        ws = null
        sessionKey = null
        retryCount = 0
    }

    fun sendMessage(envelope: WsEnvelope) {
        val json = gson.toJson(envelope)
        sendBinary(json.encodeToByteArray())
    }

    fun sendMultiDeviceMessage(senderUin: Long, receiverUin: Long, payloads: List<com.iromashka.crypto.CryptoManager.DevicePayload>) {
        val payload = mapOf(
            "type" to "Message",
            "data" to mapOf(
                "sender_uin" to senderUin,
                "receiver_uin" to receiverUin,
                "payloads" to payloads.map { mapOf("device_id" to it.device_id, "ciphertext" to it.ciphertext) },
                "timestamp" to System.currentTimeMillis()
            )
        )
        sendBinary(gson.toJson(payload).encodeToByteArray())
    }

    fun sendGroupMessage(req: GroupMessageRequest) {
        val payload = mapOf(
            "type" to "GroupMessage",
            "data" to mapOf(
                "sender_uin" to uin.toInt(),
                "group_id" to req.group_id.toInt(),
                "ciphertext" to req.ciphertext
            )
        )
        sendBinary(gson.toJson(payload).encodeToByteArray())
    }

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        val payload = mapOf(
            "type" to "Typing",
            "data" to mapOf(
                "sender_uin" to uin,
                "receiver_uin" to toUin,
                "is_typing" to isTyping
            )
        )
        sendBinary(gson.toJson(payload).encodeToByteArray())
    }

    private fun sendBinary(data: ByteArray) {
        val sk = sessionKey
        val frame = if (sk != null) {
            Transport.encodeV4(data, sk)
        } else {
            Log.w(TAG, "No session key yet, dropping message")
            return
        }
        ws?.send(okio.ByteString.of(*frame)) ?: Log.w(TAG, "WS not connected")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS TCP open, sending auth")
            retryCount = 0
            // Auth is sent as plain text (server expects text frame before session)
            val auth = gson.toJson(WsAuthPacket(uin, token))
            webSocket.send(auth)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Server sometimes sends text frames (e.g. status messages)
            handleText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val raw = bytes.toByteArray()
            val sk = sessionKey // null before auth_ok

            val decoded = Transport.decode(raw, sk)
            if (decoded == null) {
                Log.w(TAG, "Failed to decode frame ver=0x${raw.getOrNull(2)?.toString(16)}")
                return
            }
            val text = runCatching { decoded.decodeToString() }.getOrNull() ?: return
            handleText(text)
        }

        private fun handleText(text: String) {
            Log.d(TAG, "WS msg (${text.length}c): ${text.take(120)}")

            // 1. auth_ok — extract session key
            runCatching {
                val obj = org.json.JSONObject(text)
                if (obj.optString("sys") == "auth_ok") {
                    val skHex = obj.optString("sk")
                    if (skHex.isNotEmpty()) {
                        sessionKey = Transport.hexToBytes(skHex)
                        Log.i(TAG, "auth_ok received, session key set (${skHex.length / 2} bytes)")
                    }
                    scope.launch { _events.emit(WsEvent.Connected) }
                    return
                }
                // UserStatus broadcast: {"type":"UserStatus","data":{"uin":...,"status":"Online"}}
                if (obj.optString("type") == "UserStatus") {
                    val data = obj.optJSONObject("data") ?: return@runCatching
                    val u = data.optLong("uin", 0L)
                    val st = data.optString("status", "Offline")
                    if (u != 0L) {
                        scope.launch { _events.emit(WsEvent.UserStatusReceived(u, st)) }
                        return
                    }
                }
            }

            // 2. WsEnvelope (incoming message)
            val env = tryParseEnvelope(text)
            if (env != null) {
                scope.launch { _events.emit(WsEvent.MessageReceived(env)) }
                return
            }

            // 3. Typing event
            runCatching {
                val te = gson.fromJson(text, com.iromashka.model.TypingEvent::class.java)
                if (te.sender_uin != 0L && te.receiver_uin != 0L) {
                    scope.launch { _events.emit(WsEvent.TypingReceived(te)) }
                }
            }
        }

        private fun tryParseEnvelope(text: String): WsEnvelope? = runCatching {
            val env = gson.fromJson(text, WsEnvelope::class.java)
            if (env.sender_uin != 0L && env.ciphertext.isNotEmpty()) env else null
        }.getOrNull()

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing: $code $reason")
            webSocket.close(1000, null)
            sessionKey = null
            if (code == 4001) {
                scope.launch { _events.emit(WsEvent.AuthFailed) }
            } else {
                scope.launch {
                    _events.emit(WsEvent.Disconnected)
                    scheduleReconnect()
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            sessionKey = null
            scope.launch {
                _events.emit(WsEvent.Error(t.message ?: "Unknown"))
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (retryCount >= maxRetries) {
            Log.w(TAG, "Max retries reached")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = minOf(1000L * (1L shl retryCount), 30_000L)
            Log.d(TAG, "Reconnect in ${delay}ms (attempt ${retryCount + 1})")
            delay(delay)
            retryCount++
            connect()
        }
    }
}

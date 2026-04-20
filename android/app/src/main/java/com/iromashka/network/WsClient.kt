package com.iromashka.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import com.iromashka.model.WsAuthPacket
import com.iromashka.model.WsEnvelope
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.random.Random

sealed class WsEvent {
    object Connected : WsEvent()
    object Disconnected : WsEvent()
    data class MessageReceived(val envelope: WsEnvelope) : WsEvent()
    data class TypingReceived(val typing: com.iromashka.model.TypingEvent) : WsEvent()
    data class Error(val msg: String) : WsEvent()
    object AuthFailed : WsEvent()  // code 4001
}

/**
 * Transport obfuscation — mirrors серверный TransportObfuscator.
 * Формат: [IR 2B][ver 1B][len 4B LE][payload][padding 0-64B]
 */
private object TransportObfuscator {
    private val MAGIC = byteArrayOf(0x49.toByte(), 0x52.toByte())

    fun encode(data: ByteArray): ByteArray {
        val paddingLen = Random.nextInt(0, 65)
        val totalLen = 2 + 1 + 4 + data.size + paddingLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(MAGIC[0])
        buf.put(MAGIC[1])
        buf.put(0x01)
        buf.putInt(java.lang.Integer.reverseBytes(data.size))
        buf.put(data)
        repeat(paddingLen) { buf.put(Random.nextInt(256).toByte()) }
        return buf.array()
    }

    fun decode(data: ByteArray): ByteArray? {
        if (data.size < 7) return null
        val buf = ByteBuffer.wrap(data)
        val m0 = buf.get()
        val m1 = buf.get()
        if (m0 != MAGIC[0] || m1 != MAGIC[1]) return null
        val ver = buf.get()
        if (ver != 0x01.toByte()) return null
        val len = java.lang.Integer.reverseBytes(buf.int)
        if (data.size < 7 + len) return null
        val payload = ByteArray(len)
        buf.get(payload)
        return payload
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
    private val maxRetries = 5

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect() {
        val request = Request.Builder()
            .url(ApiService.WS_URL)
            .addHeader("Sec-WebSocket-Protocol", "binary, text")
            .build()
        ws = client.newWebSocket(request, listener)
        Log.d(TAG, "Connecting to ${ApiService.WS_URL}")
    }

    fun disconnect() {
        reconnectJob?.cancel()
        ws?.close(1000, "User logout")
        ws = null
        retryCount = 0
    }

    fun sendMessage(envelope: WsEnvelope) {
        val json = gson.toJson(envelope)
        ws?.let { ws ->
            val obfuscated = TransportObfuscator.encode(json.encodeToByteArray())
            val bytes = okio.ByteString.of(*obfuscated)
            ws.send(bytes)
        } ?: Log.w(TAG, "WS not connected, dropping message")
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
        val json = Gson().toJson(payload)
        ws?.let { ws ->
            val obfuscated = TransportObfuscator.encode(json.encodeToByteArray())
            val bytes = okio.ByteString.of(*obfuscated)
            ws.send(bytes)
        }
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
        val json = gson.toJson(payload)
        ws?.let { ws ->
            val obfuscated = TransportObfuscator.encode(json.encodeToByteArray())
            val bytes = okio.ByteString.of(*obfuscated)
            ws.send(bytes)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS opened")
            retryCount = 0
            val auth = gson.toJson(WsAuthPacket(uin, token))
            webSocket.send(auth)
            scope.launch { _events.emit(WsEvent.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val env = tryParseEnvelope(text)
            if (env != null) {
                scope.launch { _events.emit(WsEvent.MessageReceived(env)) }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val decoded = TransportObfuscator.decode(bytes.toByteArray())
            if (decoded == null) {
                Log.w(TAG, "Failed to decode obfuscated frame")
                return
            }
            val text = decoded.decodeToString()
            handleMessage(text)
        }

        private fun handleMessage(text: String) {
            Log.d(TAG, "WS msg: $text")
            // Try envelope
            val env = tryParseEnvelope(text)
            if (env != null) {
                scope.launch { _events.emit(WsEvent.MessageReceived(env)) }
                return
            }
            // Try typing
            runCatching {
                val te = gson.fromJson(text, com.iromashka.model.TypingEvent::class.java)
                if (te.sender_uin != 0L && te.receiver_uin != 0L) {
                    scope.launch { _events.emit(WsEvent.TypingReceived(te)) }
                }
            }
        }

        private fun tryParseEnvelope(text: String): WsEnvelope? =
            runCatching { gson.fromJson(text, WsEnvelope::class.java) }.getOrNull()

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing: $code $reason")
            webSocket.close(1000, null)
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
            scope.launch {
                _events.emit(WsEvent.Error(t.message ?: "Unknown error"))
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (retryCount >= maxRetries) {
            Log.w(TAG, "Max retries reached, giving up")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delay = minOf(1000L * (1 shl retryCount), 30_000L)
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt ${retryCount + 1})")
            delay(delay)
            retryCount++
            connect()
        }
    }
}

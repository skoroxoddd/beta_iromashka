package com.iromashka.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import com.iromashka.model.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.ChaCha20ParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

sealed class WsEvent {
    object Connected : WsEvent()
    object Disconnected : WsEvent()
    data class MessageReceived(val envelope: WsEnvelope) : WsEvent()
    data class TypingReceived(val senderUin: Long, val isTyping: Boolean) : WsEvent()
    data class ReadReceiptReceived(val senderUin: Long, val readUntil: Long) : WsEvent()
    data class MsgAckReceived(val ack: WsMsgAckData) : WsEvent()
    data class StatusChanged(val senderUin: Long, val status: String) : WsEvent()
    data class Kicked(val reason: String) : WsEvent()
    data class Error(val msg: String) : WsEvent()
    object AuthFailed : WsEvent()
}

object TransportObfuscator {

    private val MAGIC_V1 = byteArrayOf(0x49.toByte(), 0x52.toByte())

    fun encodeV3(data: ByteArray, sessionKey: ByteArray): ByteArray {
        check(data.size <= 65536) { "Payload too large: ${data.size}" }
        val nonce = Random.Default.nextBytes(12)
        val cipher = Cipher.getInstance("ChaCha20")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
        val encrypted = cipher.doFinal(data)

        val paddingLen = Random.Default.nextInt(0, 65)
        val magic = Random.Default.nextBytes(2)
        val totalLen = 2 + 1 + 12 + encrypted.size + paddingLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(magic)
        buf.put(0x03)
        buf.put(nonce)
        buf.put(encrypted)
        repeat(paddingLen) { buf.put(Random.Default.nextInt(256).toByte()) }
        return buf.array()
    }

    fun decodeV3(data: ByteArray, sessionKey: ByteArray): ByteArray? {
        if (data.size < 15) return null
        if (data[2] != 0x03.toByte()) return null
        val nonce = data.sliceArray(3..14)
        val ciphertext = data.sliceArray(15 until data.size)
        return runCatching {
            val cipher = Cipher.getInstance("ChaCha20")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "ChaCha20"), ChaCha20ParameterSpec(nonce, 0))
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    fun decodeV2(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        if (data[2] != 0x02.toByte()) return null
        val xorKey = data[3].toInt() and 0xFF
        val len = ByteBuffer.wrap(data, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        if (len > 65536 || data.size < 8 + len) return null
        val payload = data.copyOfRange(8, 8 + len)
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor (xorKey + i)).toByte()
        }
        return payload
    }

    fun decodeV1(data: ByteArray): ByteArray? {
        if (data.size < 7) return null
        if (data[0] != MAGIC_V1[0] || data[1] != MAGIC_V1[1]) return null
        if (data[2] != 0x01.toByte()) return null
        val len = ByteBuffer.wrap(data, 3, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        if (len > 65536 || data.size < 7 + len) return null
        return data.copyOfRange(7, 7 + len)
    }

    fun encode(data: ByteArray): ByteArray {
        check(data.size <= 65536) { "Payload too large: ${data.size}" }
        val paddingLen = Random.Default.nextInt(0, 65)
        val totalLen = 7 + data.size + paddingLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.put(MAGIC_V1[0])
        buf.put(MAGIC_V1[1])
        buf.put(0x01)
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.size)
        buf.put(data)
        repeat(paddingLen) { buf.put(Random.Default.nextInt(256).toByte()) }
        return buf.array()
    }

    fun decode(data: ByteArray, sessionKey: ByteArray? = null): ByteArray? {
        if (data.size < 7) return null
        return when (data[2].toInt() and 0xFF) {
            0x03 -> if (sessionKey != null) decodeV3(data, sessionKey) else null
            0x02 -> decodeV2(data)
            0x01 -> decodeV1(data)
            else -> null
        }
    }
}

class WsClient(
    private val scope: CoroutineScope,
    private val uinProvider: () -> Long,
    private val tokenProvider: () -> String
) {
    private val TAG = "WsClient"
    private val gson = Gson()

    private val _events = MutableSharedFlow<WsEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<WsEvent> = _events.asSharedFlow()

    @Volatile
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var retryCount = 0
    private val maxRetries = 10

    private var sessionKey: ByteArray? = null

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    fun connect() {
        val request = Request.Builder()
            .url(ApiService.WS_URL)
            .build()
        ws = okHttpClient.newWebSocket(request, listener)
        Log.d(TAG, "Connecting to ${ApiService.WS_URL}")
    }

    fun disconnect() {
        reconnectJob?.cancel()
        ws?.close(1000, "User disconnect")
        ws = null
        retryCount = 0
        sessionKey = null
    }

    fun sendPersonalMessage(receiverUin: Long, ciphertext: String) {
        sendTyped("Message", WsMessageData(
            sender_uin = 0,
            receiver_uin = receiverUin,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis() / 1000
        ))
    }

    fun sendGroupMessage(groupId: Long, ciphertext: String) {
        sendTyped("GroupMessage", WsMessageData(
            sender_uin = 0,
            ciphertext = ciphertext,
            timestamp = System.currentTimeMillis() / 1000
        ))
    }

    fun sendTyping(toUin: Long, isTyping: Boolean) {
        sendTyped("Typing", WsTypingData(
            sender_uin = 0,
            receiver_uin = toUin,
            is_typing = isTyping
        ))
    }

    fun sendReadReceipt(senderUin: Long, readUntil: Long) {
        sendTyped("ReadReceipt", WsReadReceiptData(
            sender_uin = 0,
            receiver_uin = senderUin,
            read_until = readUntil
        ))
    }

    fun sendMsgAck(senderUin: Long, msgTime: Long, status: String) {
        sendTyped("MsgAck", WsMsgAckData(
            sender_uin = 0,
            receiver_uin = senderUin,
            msg_time = msgTime,
            status = status
        ))
    }

    fun sendStatusChange(status: String) {
        sendTyped("ChangeStatus", WsChangeStatusData(status = status))
    }

    private fun sendTyped(type: String, data: WsData) {
        val payload = gson.toJson(WsTypedEnvelope(type = type, data = data))
        val obfuscated = TransportObfuscator.encode(payload.encodeToByteArray())
        val byteString = okio.ByteString.of(*obfuscated)
        ws?.send(byteString) ?: Log.w(TAG, "WS not connected, dropping $type")
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS opened")
            retryCount = 0
            val auth = gson.toJson(WsAuthPacket(uinProvider(), tokenProvider()))
            webSocket.send(auth)
            scope.launch { _events.emit(WsEvent.Connected) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val decoded = TransportObfuscator.decode(bytes.toByteArray(), sessionKey)
                ?: run { Log.w(TAG, "Failed to decode obfuscated frame"); return }
            handleTextMessage(decoded.decodeToString())
        }

        private fun handleTextMessage(text: String) {
            val sys = runCatching { gson.fromJson(text, WsSystemMsg::class.java) }.getOrNull()
            if (sys != null) {
                when (sys.sys) {
                    "auth_ok" -> {
                        val authOk = runCatching { gson.fromJson(text, WsAuthOk::class.java) }.getOrNull()
                        authOk?.sk?.let { skHex ->
                            sessionKey = skHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            Log.d(TAG, "Session key received")
                        }
                        return
                    }
                    "kicked" -> {
                        val reason = runCatching {
                            gson.fromJson(text, com.google.gson.JsonObject::class.java)
                                .get("reason")?.asString ?: ""
                        }.getOrDefault("")
                        scope.launch { _events.emit(WsEvent.Kicked(reason)) }
                        return
                    }
                    else -> return
                }
            }

            val typed = runCatching { gson.fromJson(text, WsTypedEnvelope::class.java) }.getOrNull()
            if (typed != null && typed.data != null) {
                when (typed.type) {
                    "Message", "GroupMessage" -> {
                        val d = typed.data as WsMessageData
                        val env = WsEnvelope(
                            sender_uin = d.sender_uin,
                            receiver_uin = d.receiver_uin,
                            ciphertext = d.ciphertext,
                            timestamp = d.timestamp
                        )
                        scope.launch { _events.emit(WsEvent.MessageReceived(env)) }
                    }
                    "Typing" -> {
                        val d = typed.data as WsTypingData
                        scope.launch { _events.emit(WsEvent.TypingReceived(d.sender_uin, d.is_typing)) }
                    }
                    "ReadReceipt" -> {
                        val d = typed.data as WsReadReceiptData
                        scope.launch { _events.emit(WsEvent.ReadReceiptReceived(d.sender_uin, d.read_until)) }
                    }
                    "MsgAck" -> {
                        val d = typed.data as WsMsgAckData
                        scope.launch { _events.emit(WsEvent.MsgAckReceived(d)) }
                    }
                    "ChangeStatus" -> {
                        val d = typed.data as WsChangeStatusData
                        scope.launch { _events.emit(WsEvent.StatusChanged(0, d.status)) }
                    }
                }
                return
            }

            val env = runCatching { gson.fromJson(text, WsEnvelope::class.java) }.getOrNull()
            if (env != null && env.sender_uin != 0L) {
                scope.launch { _events.emit(WsEvent.MessageReceived(env)) }
                return
            }

            Log.d(TAG, "Unhandled WS msg: $text")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WS closing: $code $reason")
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
            val delay = minOf(1000L * (1 shl retryCount), 60_000L)
            Log.d(TAG, "Reconnecting in ${delay}ms (attempt ${retryCount + 1})")
            delay(delay)
            retryCount++
            connect()
        }
    }
}

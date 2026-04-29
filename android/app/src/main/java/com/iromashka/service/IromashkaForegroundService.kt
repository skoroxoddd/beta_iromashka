package com.iromashka.service

import android.app.*
import android.content.*
import android.os.*
import android.os.Build
import androidx.core.app.*
import com.iromashka.MainActivity
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.WsAuthPacket
import com.iromashka.model.WsEnvelope
import com.iromashka.network.ApiService
import com.iromashka.storage.Prefs
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.iromashka.network.ChaCha20

class IromashkaForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "iromashka_fg"
        const val MSG_CHANNEL_ID = "iromashka_msg"
        const val NOTIF_ID = 1
        const val MSG_NOTIF_ID = 2

        fun startIntent(ctx: Context, pin: String = ""): Intent =
            Intent(ctx, IromashkaForegroundService::class.java).apply {
                putExtra("pin", pin)
            }
    }

    private val gson = Gson()
    private var ws: WebSocket? = null
    private var myPrivKey: java.security.PrivateKey? = null
    private var myUin: Long = -1L
    private var token: String = ""
    private var reconnectCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var sessionKey: ByteArray? = null

    private val wakeLockTimeoutMs = 30 * 60 * 1000L // 30 minutes
    private val wakeLockHandler = Handler(Looper.getMainLooper())
    private val wakeLockRenewRunnable = object : Runnable {
        override fun run() {
            runCatching {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                    it.acquire(wakeLockTimeoutMs)
                }
            }
            wakeLockHandler.postDelayed(this, wakeLockTimeoutMs - 60_000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iromashka:ws")
                .apply { setReferenceCounted(false); acquire(wakeLockTimeoutMs) }
            wakeLockHandler.postDelayed(wakeLockRenewRunnable, wakeLockTimeoutMs - 60_000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }

        val pin = intent?.getStringExtra("pin") ?: ""
        myUin = Prefs.getUin(this)
        token = Prefs.getToken(this)
        if (myUin < 0 || token.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        runCatching {
            myPrivKey = CryptoManager.unwrapPrivateKey(
                Prefs.getWrappedPriv(this), pin)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("АйРомашка")
            .setContentText("Подключено")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).setSilent(true).build()

        startForeground(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) NOTIF_ID else NOTIF_ID, notif)
        connectWebSocket()
        return START_STICKY
    }

    private fun connectWebSocket() {
        sessionKey = null
        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(ApiService.WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectCount = 0
                webSocket.send(gson.toJson(WsAuthPacket(myUin, token)))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleText(text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val decoded = FgTransport.decode(raw, sessionKey)
                if (decoded == null) return
                val text = runCatching { decoded.decodeToString() }.getOrNull() ?: return
                handleText(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sessionKey = null
                reconnectCount++
                Handler(Looper.getMainLooper()).postDelayed({ connectWebSocket() }, 5000L.coerceAtMost(30000L * reconnectCount))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                sessionKey = null
                webSocket.close(1000, null)
            }
        })
    }

    private fun handleText(text: String) {
        runCatching {
            val obj = org.json.JSONObject(text)
            if (obj.optString("sys") == "auth_ok") {
                val skHex = obj.optString("sk")
                if (skHex.isNotEmpty()) {
                    sessionKey = FgTransport.hexToBytes(skHex)
                }
                return
            }
        }
        val env = tryParseEnvelope(text)
        if (env != null && myPrivKey != null) {
            val pt = runCatching { CryptoManager.decryptMessage(env.ciphertext, myPrivKey!!) }.getOrNull() ?: ""
            if (pt.isNotEmpty()) showMsgNotif(env.sender_uin, pt)
        }
    }

    private fun tryParseEnvelope(text: String): WsEnvelope? =
        runCatching { gson.fromJson(text, WsEnvelope::class.java) }.getOrNull()?.takeIf { it.sender_uin != 0L && it.ciphertext.isNotEmpty() }

    private fun showMsgNotif(fromUin: Long, text: String) {
        val ch = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setContentTitle("Сообщение от $fromUin")
            .setContentText(text.take(100))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).setDefaults(NotificationCompat.DEFAULT_ALL)
        getSystemService(NotificationManager::class.java).notify(MSG_NOTIF_ID, ch.build())
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fg = NotificationChannel(CHANNEL_ID, "АйРомашка — соединение", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); setShowBadge(false) }
            val msg = NotificationChannel(MSG_CHANNEL_ID, "АйРомашка — сообщения", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(fg, msg))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionKey = null
        ws?.close(1000, null)
        wakeLockHandler.removeCallbacks(wakeLockRenewRunnable)
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
    }
}

private object FgTransport {
    fun decode(data: ByteArray, sessionKey: ByteArray?): ByteArray? {
        if (data.size < 3) return null
        return when (data[2]) {
            0x04.toByte(), 0x03.toByte() -> decodeV4(data, sessionKey ?: return null)
            0x02.toByte() -> decodeV2(data)
            0x01.toByte() -> decodeV1(data)
            else -> null
        }
    }

    private fun decodeV4(data: ByteArray, key: ByteArray): ByteArray? {
        if (data.size < 15) return null
        val nonce = data.sliceArray(3..14)
        val ciphertext = data.sliceArray(15 until data.size)
        return chacha20Crypt(key, nonce, ciphertext)
    }

    private fun decodeV2(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        val xorKey = data[3].toInt() and 0xFF
        val len = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > 2 * 1024 * 1024 || data.size < 8 + len) return null
        val payload = data.sliceArray(8 until 8 + len)
        return ByteArray(len) { i -> (payload[i].toInt() xor ((xorKey + i) and 0xFF)).toByte() }
    }

    private fun decodeV1(data: ByteArray): ByteArray? {
        if (data.size < 7) return null
        val len = ByteBuffer.wrap(data, 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0 || len > 2 * 1024 * 1024 || data.size < 7 + len) return null
        return data.sliceArray(7 until 7 + len)
    }

    private fun chacha20Crypt(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val cipher = Cipher.getInstance("ChaCha20")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            cipher.doFinal(data)
        } else {
            ChaCha20.crypt(key, nonce, data)
        }
    }.getOrNull()

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}

package com.iromashka.service

import android.app.*
import android.content.*
import android.os.*
import android.os.Build
import androidx.core.app.*
import com.iromashka.MainActivity
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.WsAuthPacket
import com.iromashka.network.ApiService
import com.iromashka.storage.Prefs
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

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

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iromashka:ws")
                .apply { setReferenceCounted(false); acquire(8 * 60 * 60 * 1000L) }
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
        val client = OkHttpClient.Builder()
            .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(ApiService.WS_URL).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectCount = 0
                webSocket.send(gson.toJson(WsAuthPacket(myUin, token)))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val env = tryParseEnvelope(text)
                if (env != null && myPrivKey != null) {
                    val pt = runCatching { CryptoManager.decryptMessage(env.ciphertext, myPrivKey!!) }.getOrNull() ?: ""
                    if (pt.isNotEmpty()) showMsgNotif(env.sender_uin, pt)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnectCount++
                Handler(android.os.Looper.getMainLooper()).postDelayed({ connectWebSocket() }, 5000L.coerceAtMost(30000L * reconnectCount))
            }
        })
    }

    private fun tryParseEnvelope(text: String): com.iromashka.model.WsEnvelope? =
        runCatching { gson.fromJson(text, com.iromashka.model.WsEnvelope::class.java) }.getOrNull()

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
        ws?.close(1000, null)
        runCatching { wakeLock?.release() }
    }
}

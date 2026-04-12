package com.iromashka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.iromashka.MainActivity
import com.iromashka.R
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.WsEvent
import com.iromashka.network.WsClient
import com.iromashka.storage.AppDatabase
import com.iromashka.storage.MessageEntity
import com.iromashka.storage.Prefs
import kotlinx.coroutines.*
import android.util.Log

class IromashkaForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wsClient: WsClient? = null
    private var myPrivKey: java.security.PrivateKey? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopService()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(this, "Подключение..."))

        // Connect WebSocket
        val uin = Prefs.getUin(this)
        val token = Prefs.getToken(this)
        if (uin > 0 && token.isNotEmpty()) {
            connectWs(uin, token)
        } else {
            Log.w(TAG, "No credentials for WS")
            stopSelf()
        }

        return START_STICKY
    }

    private fun connectWs(uin: Long, token: String) {
        // Try load private key
        val wrapped = Prefs.getWrappedPriv(this)
        if (wrapped.isNotEmpty()) {
            // We don't have PIN in service — key must be loaded from memory or we skip E2E
            // For now, WS will receive encrypted messages but can't decrypt
            // Decryption happens when ChatViewModel is active
        }

        wsClient = WsClient(
            scope = serviceScope,
            uinProvider = { uin },
            tokenProvider = { token }
        ).also { ws ->
            serviceScope.launch {
                ws.events.collect { event -> handleWsEvent(event) }
            }
            ws.connect()
            updateNotification("Подключено")
        }
    }

    private suspend fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Connected -> {
                Log.d(TAG, "WS connected")
                updateNotification("Подключено")
            }
            is WsEvent.Disconnected -> {
                Log.d(TAG, "WS disconnected")
                updateNotification("Переподключение...")
            }
            is WsEvent.MessageReceived -> {
                val env = (event as WsEvent.MessageReceived).envelope
                Log.d(TAG, "Message from ${env.sender_uin}")
                val msg = MessageEntity(
                    senderUin = env.sender_uin,
                    receiverUin = env.receiver_uin,
                    plaintext = env.ciphertext,
                    timestamp = if (env.timestamp > 0) env.timestamp * 1000 else System.currentTimeMillis(),
                    isMine = false
                )
                AppDatabase.getInstance(this).messageDao().insert(msg)
                updateNotification("Новое сообщение")
            }
            is WsEvent.AuthFailed -> {
                Log.w(TAG, "WS auth failed")
                updateNotification("Ошибка авторизации")
            }
            is WsEvent.Kicked -> {
                Log.w(TAG, "Kicked: ${(event as WsEvent.Kicked).reason}")
                updateNotification("Сессия завершена")
            }
            is WsEvent.Error -> {
                Log.e(TAG, "WS error: ${(event as WsEvent.Error).msg}")
                updateNotification("Ошибка соединения")
            }
            else -> {}
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(this, text))
    }

    private fun stopService() {
        wsClient?.disconnect()
        wsClient = null
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopService()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "АйРомашка — соединение",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Статус подключения" }

        val msgChannel = NotificationChannel(
            CHANNEL_MSG_ID, "АйРомашка — сообщения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Новые сообщения"
            enableVibration(true)
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(msgChannel)
    }

    companion object {
        private const val TAG = "ForegroundService"
        private const val CHANNEL_ID = "iromashka_connection"
        private const val CHANNEL_MSG_ID = "iromashka_messages"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_START = "start"
        private const val EXTRA_PIN = "pin"

        fun startIntent(ctx: Context, pin: String): Intent {
            return Intent(ctx, IromashkaForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PIN, pin)
            }
        }

        fun stopIntent(ctx: Context): Intent {
            return Intent(ctx, IromashkaForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun buildNotification(ctx: Context, text: String): Notification {
            val intent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val stopIntent = Intent(ctx, IromashkaForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPi = PendingIntent.getService(ctx, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setContentTitle("АйРомашка")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopPi)
                .setOngoing(true)
                .build()
        }
    }
}

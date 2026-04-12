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
import com.iromashka.App
import com.iromashka.MainActivity
import com.iromashka.R
import com.iromashka.network.WsEvent
import com.iromashka.storage.AppDatabase
import com.iromashka.storage.MessageEntity
import com.iromashka.storage.Prefs
import kotlinx.coroutines.*
import android.util.Log

class IromashkaForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopService()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(this, "Подключение..."))

        val uin = Prefs.getUin(this)
        val token = Prefs.getToken(this)
        if (uin > 0 && token.isNotEmpty()) {
            App.instance.wsHolder.connect(uin, token)
            startListening()
        } else {
            Log.w(TAG, "No credentials")
            stopSelf()
        }

        return START_STICKY
    }

    private fun startListening() {
        eventJob?.cancel()
        eventJob = serviceScope.launch {
            App.instance.wsHolder.events.collect { event ->
                when (event) {
                    is WsEvent.Connected -> updateNotification("Подключено")
                    is WsEvent.Disconnected -> updateNotification("Переподключение...")
                    is WsEvent.MessageReceived -> {
                        val env = event.envelope
                        Log.d(TAG, "Message from ${env.sender_uin}")
                        val msg = MessageEntity(
                            senderUin = env.sender_uin,
                            receiverUin = env.receiver_uin,
                            plaintext = env.ciphertext,
                            timestamp = if (env.timestamp > 0) env.timestamp * 1000 else System.currentTimeMillis(),
                            isMine = false
                        )
                        AppDatabase.getInstance(this@IromashkaForegroundService).messageDao().insert(msg)
                        updateNotification("Новое сообщение")
                    }
                    is WsEvent.AuthFailed -> updateNotification("Ошибка авторизации")
                    is WsEvent.Kicked -> updateNotification("Сессия завершена")
                    is WsEvent.Error -> updateNotification("Ошибка соединения")
                    else -> {}
                }
            }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(this, text))
    }

    private fun stopService() {
        eventJob?.cancel()
        App.instance.wsHolder.disconnect()
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
        private const val TAG = "FGService"
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

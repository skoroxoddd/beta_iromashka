package com.iromashka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.iromashka.MainActivity
import com.iromashka.R
import com.iromashka.crypto.CryptoManager
import com.iromashka.model.*
import com.iromashka.network.WsClient
import com.iromashka.network.WsEvent
import com.iromashka.storage.Prefs
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class IromashkaForegroundService : LifecycleService() {

    private var wsClient: WsClient? = null
    private var myPrivKey: java.security.PrivateKey? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            wsClient?.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return START_NOT_STICKY
        }

        val pin = intent?.getStringExtra(EXTRA_PIN) ?: return START_NOT_STICKY

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Подключение..."))

        lifecycleScope.launch {
            val wrapped = Prefs.getWrappedPriv(this@IromashkaForegroundService)
            if (wrapped.isNotEmpty()) {
                myPrivKey = runCatching { CryptoManager.unwrapPrivateKey(wrapped, pin) }.getOrNull()
            }

            val uin = Prefs.getUin(this@IromashkaForegroundService)
            val token = Prefs.getToken(this@IromashkaForegroundService)
            if (uin > 0 && token.isNotEmpty()) {
                wsClient = WsClient(
                    scope = lifecycleScope,
                    uinProvider = { Prefs.getUin(this@IromashkaForegroundService) },
                    tokenProvider = { Prefs.getToken(this@IromashkaForegroundService) }
                ).also { ws ->
                    launch {
                        ws.events.collect { event ->
                            when (event) {
                                is WsEvent.Connected -> updateNotification("Подключено")
                                is WsEvent.Disconnected -> updateNotification("Переподключение...")
                                is WsEvent.MessageReceived -> handleIncomingMessage(event.envelope)
                                else -> {}
                            }
                        }
                    }
                    ws.connect()
                }
            }
        }

        return START_STICKY
    }

    private suspend fun handleIncomingMessage(envelope: WsEnvelope) {
        val privKey = myPrivKey ?: return
        val plaintext = CryptoManager.decryptMessage(envelope.ciphertext, privKey) ?: return
        showMsgNotification(envelope.sender_uin, plaintext)
    }

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

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, IromashkaForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("АйРомашка")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun showMsgNotification(senderUin: Long, text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_MSG_ID)
            .setContentTitle("Сообщение от UIN $senderUin")
            .setContentText(text.take(100))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_MSG_ID, notif)
    }

    companion object {
        private const val CHANNEL_ID = "iromashka_connection"
        private const val CHANNEL_MSG_ID = "iromashka_messages"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_MSG_ID = 2
        private const val EXTRA_PIN = "extra_pin"
        private const val ACTION_STOP = "stop"

        fun startIntent(ctx: Context, pin: String): Intent {
            return Intent(ctx, IromashkaForegroundService::class.java).apply {
                putExtra(EXTRA_PIN, pin)
            }
        }

        fun stopIntent(ctx: Context): Intent {
            return Intent(ctx, IromashkaForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}

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

class IromashkaForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(this, "Работает"))

        return START_STICKY
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

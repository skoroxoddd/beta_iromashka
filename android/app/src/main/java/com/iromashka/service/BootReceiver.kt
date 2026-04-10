package com.iromashka.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.iromashka.storage.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isLoggedIn(context)) {
                val svcIntent = IromashkaForegroundService.startIntent(context, "")
                context.startForegroundService(svcIntent)
            }
        }
    }
}

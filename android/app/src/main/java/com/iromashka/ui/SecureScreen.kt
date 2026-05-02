package com.iromashka.ui

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * H4: ставит FLAG_SECURE на текущий window — экран не попадёт в скриншот
 * системы, в screen recorder и в превью recents. Снимаем при выходе с экрана,
 * чтобы остальной UI оставался шарящимся.
 *
 * Использовать на: GenerateMnemonic, RestoreFromMnemonic, UinReveal, PinUnlock.
 */
@Composable
fun SecureScreen() {
    val ctx = LocalContext.current
    DisposableEffect(ctx) {
        val act = ctx.findActivity()
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * H4: copy в буфер с пометкой "sensitive" (Android 13+ скрывает превью toast-а)
 * + auto-clear через [autoClearMs] миллисекунд.
 *
 * Не блокирует. Если буфер уже изменился к моменту таймера — ничего не трогаем,
 * чтобы не стереть то что юзер скопировал позже.
 */
fun copySensitive(ctx: Context, label: String, text: String, autoClearMs: Long = 60_000L) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = android.content.ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val extras = PersistableBundle()
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        clip.description.extras = extras
    }
    cm.setPrimaryClip(clip)

    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching {
            val current = cm.primaryClip ?: return@runCatching
            if (current.itemCount > 0 && current.getItemAt(0).text?.toString() == text) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    cm.clearPrimaryClip()
                } else {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                }
            }
        }
    }, autoClearMs)
}

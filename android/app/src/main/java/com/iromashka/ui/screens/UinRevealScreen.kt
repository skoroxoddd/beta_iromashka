package com.iromashka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette

@Composable
fun UinRevealScreen(
    uin: Long,
    onContinue: () -> Unit
) {
    val p = LocalThemePalette.current
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        Text("Оплата прошла", fontSize = 16.sp, color = p.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text("Ваш UIN", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.textPrimary)

        Spacer(Modifier.height(24.dp))

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = p.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    uin.toString(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = p.accent
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Сохраните UIN — он нужен для входа.\nЕсли забудете — найдём по номеру телефона.",
                    fontSize = 13.sp,
                    color = p.textSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("UIN", uin.toString()))
                        Toast.makeText(ctx, "UIN скопирован", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Скопировать UIN", color = p.accent)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent)
        ) {
            Text("Продолжить — создать PIN", fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

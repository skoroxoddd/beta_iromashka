package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import kotlinx.coroutines.delay

@Composable
fun PinUnlockScreen(
    onUnlock: (String) -> Boolean,
    onWipeAndLogout: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    var remainingSecs by remember { mutableStateOf(Prefs.getPinLockoutRemainingSecs(ctx)) }

    LaunchedEffect(Unit) {
        while (remainingSecs > 0) {
            delay(1000L)
            remainingSecs = Prefs.getPinLockoutRemainingSecs(ctx)
        }
    }

    val isLocked = remainingSecs > 0
    val lockoutText = formatTime(remainingSecs)
    val fails = Prefs.getPinFailures(ctx)
    val attemptsRemaining = maxOf(0, Prefs.MAX_PIN_FAILURES - fails)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(palette.titleBar))
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Text("АйРомашка", color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center))
        }

        Text("Разблокировка", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = palette.textPrimary)

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = {
                if (it.length <= 6 && !isLocked) {
                    pin = it.filter { c -> c.isDigit() }
                    isError = false
                }
            },
            label = { Text("PIN-код", color = palette.textSecondary) },
            singleLine = true,
            enabled = !isLocked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = isError,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.divider,
                errorBorderColor = palette.errorRed,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                disabledTextColor = palette.textSecondary,
            ),
        )

        Spacer(Modifier.height(12.dp))

        if (isLocked) {
            Text("Попробуйте через", color = palette.textSecondary, fontSize = 13.sp)
            Text(lockoutText, color = palette.errorRed, fontSize = 24.sp,
                fontWeight = FontWeight.Bold)
        } else if (fails > 0 && fails < Prefs.MAX_PIN_FAILURES) {
            Text("Осталось попыток: $attemptsRemaining",
                color = palette.textSecondary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (pin.length >= 6 && !isLocked) {
                    isLoading = true
                    if (!onUnlock(pin)) {
                        Prefs.recordPinFailure(ctx)
                        isError = true
                        pin = ""
                        remainingSecs = Prefs.getPinLockoutRemainingSecs(ctx)
                        // 3 неверных попытки → удаление истории
                        if (Prefs.getPinFailures(ctx) >= Prefs.MAX_PIN_FAILURES) {
                            onWipeAndLogout()
                        }
                    } else {
                        Prefs.resetPinFailures(ctx)
                    }
                    isLoading = false
                }
            },
            enabled = pin.length >= 6 && !isLoading && !isLocked,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("Разблокировать")
            }
        }
    }
}

private fun formatTime(secs: Int): String {
    val m = secs / 60
    val s = secs % 60
    return String.format("%02d:%02d", m, s)
}

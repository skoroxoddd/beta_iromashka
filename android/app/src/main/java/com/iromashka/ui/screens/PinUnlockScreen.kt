package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.iromashka.R
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import kotlinx.coroutines.delay

@Composable
fun PinUnlockScreen(onUnlock: (String) -> Boolean) {
    val p = LocalThemePalette.current
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var remainingSecs by remember { mutableStateOf(Prefs.getPinLockoutRemainingSecs(ctx)) }

    LaunchedEffect(Unit) {
        while (remainingSecs > 0) { delay(1000L); remainingSecs = Prefs.getPinLockoutRemainingSecs(ctx) }
    }

    val isLocked = remainingSecs > 0
    val fails = Prefs.getPinFailures(ctx)

    Box(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.accent))

        Column(
            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.height(12.dp))
            Text("АйРомашка", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = p.textPrimary)
            Text("Введите PIN для входа", fontSize = 13.sp, color = p.textSecondary)

            Spacer(Modifier.height(32.dp))

            Card(
                modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = p.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {

                    OutlinedTextField(
                        value = pin,
                        onValueChange = {
                            if (it.length <= 6 && !isLocked) {
                                pin = it.filter { c -> c.isDigit() }
                                isError = false
                            }
                        },
                        label = { Text("PIN-код", color = p.textSecondary) },
                        singleLine = true,
                        enabled = !isLocked,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = isError,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (isError) p.errorRed else p.accent,
                            unfocusedBorderColor = if (isError) p.errorRed else p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )

                    if (isError) {
                        Spacer(Modifier.height(4.dp))
                        Text("Неверный PIN-код", color = p.errorRed, fontSize = 12.sp)
                    }

                    if (isLocked) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(p.errorRed.copy(alpha = 0.1f)).padding(10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Попробуйте через ${formatTime(remainingSecs)}", color = p.errorRed, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (fails > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("Попыток осталось: ${maxOf(0, 5 - (fails % 5))}", color = p.textMuted, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (pin.length >= 6 && !isLocked) {
                                isLoading = true
                                if (!onUnlock(pin)) {
                                    Prefs.recordPinFailure(ctx)
                                    isError = true; pin = ""
                                    remainingSecs = Prefs.getPinLockoutRemainingSecs(ctx)
                                } else {
                                    Prefs.resetPinFailures(ctx)
                                }
                                isLoading = false
                            }
                        },
                        enabled = pin.length >= 6 && !isLoading && !isLocked,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Войти", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(secs: Int): String = String.format("%02d:%02d", secs / 60, secs % 60)

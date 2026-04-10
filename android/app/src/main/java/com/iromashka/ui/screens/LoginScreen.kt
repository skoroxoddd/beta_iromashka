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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthState
import com.iromashka.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSuccess: (Long, String) -> Unit,
    onRegister: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val state by viewModel.state.collectAsState()

    var uinInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }

    val remainingSecs by produceState(initialValue = Prefs.getPinLockoutRemainingSecs(ctx)) {
        val lockoutTime = Prefs.getPinLockoutTime(ctx)
        val fails = Prefs.getPinFailures(ctx)
        val durMs = getLockoutDurationMillis(fails)
        while (value > 0) {
            delay(1000L)
            value = Prefs.getPinLockoutRemainingSecs(ctx)
        }
    }

    LaunchedEffect(state) {
        if (state is AuthState.Success) {
            onSuccess((state as AuthState.Success).uin, pinInput)
        }
    }

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
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Text("АйРомашка", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.weight(1f))
            }
        }

        Text("Вход", fontSize = 26.sp, fontWeight = FontWeight.Bold,
            color = palette.textPrimary, modifier = Modifier.padding(top = 32.dp))
        Text("E2E-шифрованный мессенджер", fontSize = 14.sp,
            color = palette.textSecondary, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(24.dp))

        Card(
            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                OutlinedTextField(
                    value = uinInput,
                    onValueChange = { uinInput = it.filter { c -> c.isDigit() } },
                    label = { Text("UIN", color = palette.textSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                    )
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        if (it.length <= 6) pinInput = it.filter { c -> c.isDigit() }
                    },
                    label = { Text("PIN-код", color = palette.textSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                    )
                )
                Spacer(Modifier.height(16.dp))

                if (remainingSecs > 0) {
                    Text(
                        "Блокировка: $remainingSecs сек",
                        color = Color(0xFFFF9800),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Button(
                    onClick = {
                        if (remainingSecs > 0) return@Button
                        val uin = uinInput.toLongOrNull() ?: return@Button
                        if (pinInput.length >= 6) {
                            viewModel.login(uin, pinInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uinInput.isNotBlank() && pinInput.length >= 6 && remainingSecs == 0,
                ) {
                    if (state is AuthState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Войти")
                    }
                }

                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onRegister) {
                    Text("Нет аккаунта? Зарегистрироваться", color = palette.accent)
                }
            }
        }

        if (state is AuthState.Error) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Text(
                    (state as AuthState.Error).message,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

private fun getLockoutDurationMillis(failures: Int): Long = when {
    failures < 10 -> 30_000L
    failures < 15 -> 60_000L
    failures < 20 -> 5 * 60_000L
    failures < 25 -> 15 * 60_000L
    failures < 30 -> 30 * 60_000L
    failures < 35 -> 60 * 60_000L
    failures < 40 -> 4 * 60 * 60_000L
    else -> 24 * 60 * 60_000L
}

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthState
import com.iromashka.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSuccess: (Long) -> Unit,
    onRegister: () -> Unit
) {
    val palette = LocalThemePalette.current
    val state by viewModel.state.collectAsState()

    var uinInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is AuthState.Success) {
            onSuccess((state as AuthState.Success).uin)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Title bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(palette.titleBar))
                .padding(horizontal = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Text("АйРомашка", color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.weight(1f))
            }
        }

        Text("Вход", fontSize = 22.sp, fontWeight = FontWeight.Bold,
            color = palette.textPrimary, modifier = Modifier.padding(top = 48.dp))
        Text("АйРомашка — мессенджер для своих", fontSize = 13.sp,
            color = palette.textSecondary)

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.padding(horizontal = 32.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
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
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = {
                        if (it.length <= 6) pinInput = it.filter { c -> c.isDigit() }
                    },
                    label = { Text("PIN-код (6 цифр)", color = palette.textSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                    )
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val uin = uinInput.toLongOrNull() ?: return@Button
                        if (pinInput.length >= 6) {
                            viewModel.login(uin, pinInput)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uinInput.isNotBlank() && pinInput.length >= 6,
                ) {
                    if (state is AuthState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Войти")
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRegister) {
                    Text("Зарегистрироваться", color = palette.accent)
                }
            }
        }

        // Error
        if (state is AuthState.Error) {
            Spacer(Modifier.height(8.dp))
            Text(
                (state as AuthState.Error).message,
                color = palette.errorRed,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

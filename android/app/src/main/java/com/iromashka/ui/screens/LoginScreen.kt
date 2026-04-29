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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.iromashka.R
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthState
import com.iromashka.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onSuccess: (Long, String) -> Unit,
    onRegister: () -> Unit,
    onForgotPin: () -> Unit = {},
    onNeedsMigration: (Long, String) -> Unit = { _, _ -> }
) {
    val p = LocalThemePalette.current
    val state by viewModel.state.collectAsState()

    var uinInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        when (state) {
            is AuthState.Success -> onSuccess((state as AuthState.Success).uin, pinInput)
            is AuthState.NeedsPasswordMigration -> onNeedsMigration((state as AuthState.NeedsPasswordMigration).uin, pinInput)
            else -> {}
        }
    }

    Box(Modifier.fillMaxSize().background(p.background)
        .windowInsetsPadding(WindowInsets.systemBars)) {

        // Top accent bar
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.accent))

        Column(
            modifier = Modifier.fillMaxSize().padding(top = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {

            // Header
            Box(
                Modifier.fillMaxWidth().background(p.surface).padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("АйРомашка", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = p.textPrimary)
                        Text("Мессенджер для своих", fontSize = 12.sp, color = p.textSecondary)
                    }
                }
            }

            HorizontalDivider(color = p.divider, thickness = 1.dp)

            Spacer(Modifier.height(32.dp))

            // Card
            Card(
                modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = p.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(24.dp)) {

                    Text("Вход в аккаунт", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = p.textPrimary)
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uinInput,
                        onValueChange = { uinInput = it.filter { c -> c.isDigit() } },
                        label = { Text("UIN", color = p.textSecondary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent,
                            unfocusedBorderColor = p.divider,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg,
                            focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    val pinValid = pinInput.length >= 6
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6) pinInput = it.filter { c -> c.isDigit() } },
                        label = { Text("PIN-код", color = p.textSecondary) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (pinValid) p.accent else p.divider,
                            unfocusedBorderColor = if (pinValid) p.accent else p.divider,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg,
                            focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    val passwordValid = passwordInput.length >= 12
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("Пароль (если установлен)", color = p.textSecondary) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (passwordValid) p.accent else p.divider,
                            unfocusedBorderColor = if (passwordValid) p.accent else p.divider,
                            focusedTextColor = p.textPrimary,
                            unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg,
                            focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            val uin = uinInput.toLongOrNull() ?: return@Button
                            if (pinInput.length >= 6) viewModel.login(uin, pinInput, passwordInput.ifEmpty { null })
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = uinInput.isNotBlank() && pinInput.length >= 6,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                    ) {
                        if (state is AuthState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Войти", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
                        Text("Зарегистрироваться", color = p.accent, fontSize = 14.sp)
                    }
                    TextButton(onClick = onForgotPin, modifier = Modifier.fillMaxWidth()) {
                        Text("Забыл PIN — восстановить по фразе", color = p.accent, fontSize = 13.sp)
                    }
                }
            }

            if (state is AuthState.Error) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.padding(horizontal = 24.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(p.errorRed.copy(alpha = 0.1f))
                        .padding(12.dp)
                ) {
                    Text((state as AuthState.Error).message, color = p.errorRed, fontSize = 13.sp)
                }
            }
        }
    }
}

package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthState
import com.iromashka.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onSuccess: (Long) -> Unit,
    onBack: () -> Unit
) {
    val p = LocalThemePalette.current
    val state by viewModel.state.collectAsState()

    var nickname by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is AuthState.Success) onSuccess((state as AuthState.Success).uin)
    }

    Column(Modifier.fillMaxSize().background(p.background)) {

        // Top accent bar
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.accent))

        // Toolbar
        Box(Modifier.fillMaxWidth().background(p.surface)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = p.accent)
                }
                Text("Регистрация", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.textPrimary)
            }
        }

        HorizontalDivider(color = p.divider, thickness = 1.dp)

        Column(Modifier.fillMaxWidth().padding(24.dp)) {

            Text("Создать аккаунт", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.textPrimary)
            Text("UIN будет выдан автоматически", fontSize = 12.sp, color = p.textSecondary)

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = p.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(20.dp)) {

                    OutlinedTextField(
                        value = nickname, onValueChange = { nickname = it },
                        label = { Text("Имя / никнейм", color = p.textSecondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6) pin = it.filter { c -> c.isDigit() } },
                        label = { Text("PIN-код (6 цифр)", color = p.textSecondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pin2,
                        onValueChange = { if (it.length <= 6) pin2 = it.filter { c -> c.isDigit() } },
                        label = { Text("Повторите PIN", color = p.textSecondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = pin2.isNotEmpty() && pin != pin2,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (pin2.isNotEmpty() && pin != pin2) p.errorRed else p.accent,
                            unfocusedBorderColor = if (pin2.isNotEmpty() && pin != pin2) p.errorRed else p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )
                    if (pin2.isNotEmpty() && pin != pin2) {
                        Text("PIN-коды не совпадают", color = p.errorRed, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (nickname.isNotBlank() && pin.length >= 6 && pin == pin2)
                                viewModel.register(nickname, pin, phone)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = nickname.isNotBlank() && pin.length >= 6 && pin == pin2,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                    ) {
                        if (state is AuthState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Создать аккаунт", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                        }
                    }
                }
            }

            if (state is AuthState.Error) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(p.errorRed.copy(alpha = 0.1f)).padding(12.dp)
                ) {
                    Text((state as AuthState.Error).message, color = p.errorRed, fontSize = 13.sp)
                }
            }
        }
    }
}

package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthState
import com.iromashka.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onSuccess: (Long, String) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalThemePalette.current
    val state by viewModel.state.collectAsState()

    var phone by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }
    var pinMatchError by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        if (state is AuthState.Success) {
            onSuccess((state as AuthState.Success).uin, pin)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(palette.background)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(palette.titleBar))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                TextButton(onClick = onBack) {
                    Text("Назад", color = Color.White)
                }
                Text("Регистрация", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.weight(1f))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Text("Создать аккаунт", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Text("UIN будет выдан автоматически. Данные защищены E2E шифрованием.",
                fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Номер телефона", color = palette.textSecondary) },
                        placeholder = { Text("+79161234567") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.divider,
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = pin,
                        onValueChange = { if (it.length <= 6) pin = it.filter { c -> c.isDigit() } },
                        label = { Text("PIN-код (6 цифр)", color = palette.textSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = if (showPin) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.divider,
                        )
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = {
                            pinConfirm = it.filter { c -> c.isDigit() }
                            pinMatchError = it.isNotEmpty() && it != pin
                        },
                        label = { Text("Подтвердите PIN", color = palette.textSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = if (showPin) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = pinMatchError,
                        supportingText = if (pinMatchError) { { Text("PIN не совпадает", color = Color(0xFFD32F2F)) } } else null,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.accent,
                            unfocusedBorderColor = palette.divider,
                        )
                    )

                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = showPin,
                            onCheckedChange = { showPin = it },
                            colors = CheckboxDefaults.colors(checkedColor = palette.accent)
                        )
                        Text("Показать PIN", color = palette.textSecondary)
                    }

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (phone.length >= 10 && pin.length == 6 && pin == pinConfirm) {
                                viewModel.register(pin, phone)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = phone.length >= 10 && pin.length == 6 && pin == pinConfirm,
                    ) {
                        if (state is AuthState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Зарегистрироваться")
                        }
                    }
                }
            }

            if (state is AuthState.Error) {
                Spacer(Modifier.height(12.dp))
                Text(
                    (state as AuthState.Error).message,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

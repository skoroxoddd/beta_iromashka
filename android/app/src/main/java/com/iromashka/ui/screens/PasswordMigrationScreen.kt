package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordMigrationScreen(
    viewModel: AuthViewModel,
    uin: Long,
    onSuccess: () -> Unit,
    onSkip: () -> Unit
) {
    val p = LocalThemePalette.current

    var password by remember { mutableStateOf("") }
    var password2 by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val passwordValid = password.length >= 12 &&
        password.any { it.isUpperCase() } &&
        password.any { it.isDigit() } &&
        password.any { !it.isLetterOrDigit() }
    val passwordsMatch = password == password2

    Column(Modifier.fillMaxSize().background(p.background).windowInsetsPadding(WindowInsets.systemBars)) {

        Box(Modifier.fillMaxWidth().height(4.dp).background(p.accent))

        Box(Modifier.fillMaxWidth().background(p.surface)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                IconButton(onClick = onSkip) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Пропустить", tint = p.accent)
                }
                Text("Установка пароля", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = p.textPrimary)
            }
        }

        HorizontalDivider(color = p.divider, thickness = 1.dp)

        Column(Modifier.fillMaxWidth().padding(24.dp)) {

            Text("Повысьте безопасность", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.textPrimary)
            Text("Установите пароль для защиты аккаунта", fontSize = 12.sp, color = p.textSecondary)

            Spacer(Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = p.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(20.dp)) {

                    OutlinedTextField(
                        value = password,
                        onValueChange = { if (it.length <= 128) password = it },
                        label = { Text("Пароль (мин. 12 символов)", color = p.textSecondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )
                    if (password.isNotEmpty() && !passwordValid) {
                        Text("1 заглавная, 1 цифра, 1 спецсимвол", color = p.errorRed, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password2,
                        onValueChange = { if (it.length <= 128) password2 = it },
                        label = { Text("Повторите пароль", color = p.textSecondary) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = password2.isNotEmpty() && !passwordsMatch,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = if (password2.isNotEmpty() && !passwordsMatch) p.errorRed else p.accent,
                            unfocusedBorderColor = if (password2.isNotEmpty() && !passwordsMatch) p.errorRed else p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )
                    if (password2.isNotEmpty() && !passwordsMatch) {
                        Text("Пароли не совпадают", color = p.errorRed, fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (passwordValid && passwordsMatch) {
                                loading = true
                                error = null
                                viewModel.setPassword(password,
                                    onSuccess = {
                                        loading = false
                                        onSuccess()
                                    },
                                    onError = { msg ->
                                        loading = false
                                        error = msg
                                    }
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = passwordValid && passwordsMatch && !loading,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Установить пароль", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                        Text("Пропустить", color = p.textSecondary, fontSize = 14.sp)
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(p.errorRed.copy(alpha = 0.1f)).padding(12.dp)
                ) {
                    Text(error!!, color = p.errorRed, fontSize = 13.sp)
                }
            }
        }
    }
}

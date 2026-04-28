package com.iromashka.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.iromashka.R
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthViewModel
import com.iromashka.viewmodel.PaymentState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonePaymentScreen(
    viewModel: AuthViewModel,
    onPaid: (phone: String, uin: Long?) -> Unit,
    onBack: () -> Unit
) {
    val p = LocalThemePalette.current
    val ctx = LocalContext.current
    val state by viewModel.paymentState.collectAsState()

    var phone by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        when (val s = state) {
            is PaymentState.Created -> {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.confirmationUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                viewModel.pollPaymentStatus(s.phone)
            }
            is PaymentState.Paid -> {
                onPaid(phone.filter { it.isDigit() }, s.uin)
                viewModel.resetPayment()
            }
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize().background(p.background)) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.accent))

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

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp).clip(CircleShape)
            )
            Spacer(Modifier.height(16.dp))
            Text("iRomashka", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = p.accent)
            Text("Активация аккаунта — 100 ₽", fontSize = 13.sp, color = p.textSecondary)

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = p.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Номер телефона", color = p.textSecondary) },
                        placeholder = { Text("+7 999 123-45-67", color = p.textSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        enabled = state !is PaymentState.Created,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = p.accent, unfocusedBorderColor = p.divider,
                            focusedTextColor = p.textPrimary, unfocusedTextColor = p.textPrimary,
                            unfocusedContainerColor = p.inputBg, focusedContainerColor = p.inputBg,
                        )
                    )

                    Spacer(Modifier.height(16.dp))

                    when (val s = state) {
                        is PaymentState.Created -> {
                            Button(
                                onClick = {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(s.confirmationUrl))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(intent)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                            ) {
                                Text("Открыть страницу оплаты", fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = p.accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Ожидание оплаты…", fontSize = 12.sp, color = p.textSecondary)
                            }
                        }
                        else -> {
                            Button(
                                onClick = { viewModel.createPayment(phone) },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                enabled = phone.filter { it.isDigit() }.length >= 7 && state !is PaymentState.Loading,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = p.accent)
                            ) {
                                if (state is PaymentState.Loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Text("Оплатить — 100 ₽", fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            (state as? PaymentState.Error)?.let {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(p.errorRed.copy(alpha = 0.1f)).padding(12.dp)
                ) {
                    Text(it.message, color = p.errorRed, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "После оплаты вернитесь в приложение — мы автоматически перейдём к созданию аккаунта.",
                fontSize = 11.sp,
                color = p.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

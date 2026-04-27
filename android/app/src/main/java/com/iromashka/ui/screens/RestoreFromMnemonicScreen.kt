package com.iromashka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.iromashka.crypto.Bip39
import com.iromashka.crypto.CryptoManager
import com.iromashka.network.ApiService
import com.iromashka.network.RecoveryCompleteRequest
import com.iromashka.network.RecoveryInitRequest
import com.iromashka.network.RecoveryInitResponse
import com.iromashka.network.RecoveryLookupRequest
import com.iromashka.storage.Prefs
import kotlinx.coroutines.launch

private enum class RestoreStage { Form, NewPin, Done }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreFromMnemonicScreen(
    onSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(RestoreStage.Form) }
    var phone by remember { mutableStateOf("+7") }
    var phrase by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var newPin2 by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var initResp by remember { mutableStateOf<RecoveryInitResponse?>(null) }
    var normalizedPhrase by remember { mutableStateOf("") }

    // live phrase validation
    val (validCount, allValid) = remember(phrase) {
        runCatching {
            val norm = Bip39.normalize(phrase)
            val parts = norm.split(' ').filter { it.isNotEmpty() }
            val list = Bip39.loadWordlist(ctx).toHashSet()
            val valid = parts.count { it in list }
            valid to (parts.size == 12 && valid == 12)
        }.getOrDefault(0 to false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Восстановление по фразе") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (stage) {
                RestoreStage.Form -> {
                    Text("Введите телефон и 12 слов резервной фразы.")
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it },
                        label = { Text("Телефон") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phrase, onValueChange = { phrase = it },
                        label = { Text("12 слов через пробел") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        supportingText = {
                            val color = when {
                                allValid -> Color(0xFF27AE60)
                                validCount > 0 -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text("Распознано: $validCount из 12", color = color)
                        }
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !loading && phone.length >= 5 && allValid,
                        onClick = {
                            scope.launch {
                                loading = true; error = null
                                runCatching {
                                    val lookup = ApiService.api.recoveryLookup(RecoveryLookupRequest(phone))
                                    val uin = lookup.uin
                                        ?: throw IllegalStateException("Аккаунт с резервной фразой по этому телефону не найден")
                                    val derived = Bip39.deriveKey(ctx, phrase, uin)
                                    normalizedPhrase = derived.normalized
                                    val resp = ApiService.api.recoveryInit(
                                        RecoveryInitRequest(
                                            phone = phone,
                                            phrase_fingerprint = derived.fingerprint
                                        )
                                    )
                                    initResp = resp
                                }.onSuccess { stage = RestoreStage.NewPin }
                                 .onFailure { error = it.message ?: "Ошибка восстановления" }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loading) "Проверка…" else "Продолжить") }
                }
                RestoreStage.NewPin -> {
                    Text("Введите новый PIN. Он заменит старый, остальные сессии будут разлогинены.")
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Новый PIN (4-6 цифр)") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPin2, onValueChange = { newPin2 = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("Повторите PIN") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !loading && newPin.length >= 4 && newPin == newPin2,
                        onClick = {
                            val resp = initResp ?: return@Button
                            scope.launch {
                                loading = true; error = null
                                runCatching {
                                    val priv = Bip39.unwrap(ctx, resp.wrapped_with_seed, normalizedPhrase, resp.uin)
                                    val newWrapped = CryptoManager.wrapPrivateKey(priv, newPin)
                                    val complete = ApiService.api.recoveryComplete(
                                        RecoveryCompleteRequest(
                                            uin = resp.uin,
                                            recovery_token = resp.recovery_token,
                                            new_pin = newPin,
                                            new_encrypted_key = newWrapped,
                                            new_salt = ""
                                        )
                                    )
                                    val pubkey = runCatching {
                                        ApiService.api.getPubKey(complete.uin).pubkey
                                    }.getOrDefault("")
                                    Prefs.saveSession(
                                        ctx,
                                        uin = complete.uin,
                                        nickname = "UIN ${complete.uin}",
                                        token = complete.token,
                                        wrappedPriv = newWrapped,
                                        pubKey = pubkey,
                                        refreshToken = complete.refresh_token
                                    )
                                    Prefs.setPhone(ctx, phone)
                                    Prefs.markRecoveryPhrase(ctx, true)
                                }.onSuccess { stage = RestoreStage.Done; onSuccess() }
                                 .onFailure { error = "Не удалось завершить: ${it.message}" }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loading) "Восстановление…" else "Установить PIN и войти") }
                }
                RestoreStage.Done -> Text("Готово. Вход выполнен.")
            }
        }
    }
}

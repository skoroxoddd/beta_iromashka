package com.iromashka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.iromashka.crypto.Bip39
import com.iromashka.crypto.CryptoManager
import com.iromashka.network.ApiService
import com.iromashka.network.RecoverySaveRequest
import com.iromashka.storage.Prefs
import kotlinx.coroutines.launch

private enum class GenStage { Intro, ShowPhrase, ConfirmPin, Done }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateMnemonicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uin = Prefs.getUin(ctx)

    var stage by remember { mutableStateOf(GenStage.Intro) }
    var phrase by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasExisting = remember { Prefs.hasRecoveryPhrase(ctx) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Резервная фраза") },
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
                GenStage.Intro -> {
                    Text(
                        if (hasExisting)
                            "Резервная фраза уже создана. Можно сгенерировать новую — старая перестанет работать."
                        else
                            "Сейчас сгенерируем 12 слов. Запишите их в надёжном месте — это единственный способ восстановить аккаунт, если забудете PIN.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        enabled = !loading,
                        onClick = {
                            loading = true; error = null
                            runCatching { Bip39.generate(ctx) }
                                .onSuccess { phrase = it; stage = GenStage.ShowPhrase }
                                .onFailure { error = it.message ?: "Ошибка генерации" }
                            loading = false
                        }
                    ) { Text(if (hasExisting) "Сгенерировать новую" else "Создать фразу") }
                }
                GenStage.ShowPhrase -> {
                    Text("Запишите эти 12 слов по порядку:", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            phrase.split(' ').mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("\n"),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        "Никому не показывайте фразу. Кто её знает — может восстановить ваш аккаунт.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { stage = GenStage.ConfirmPin }) { Text("Я записал, продолжить") }
                }
                GenStage.ConfirmPin -> {
                    Text("Подтвердите свой текущий PIN, чтобы сохранить резервную копию.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !loading && pin.length >= 4,
                        onClick = {
                            scope.launch {
                                loading = true; error = null
                                runCatching {
                                    val wrappedPriv = Prefs.getWrappedPriv(ctx)
                                    require(wrappedPriv.isNotEmpty()) { "Локальный ключ не найден" }
                                    val priv = CryptoManager.unwrapPrivateKey(wrappedPriv, pin)
                                    val w = Bip39.wrap(ctx, priv, phrase, uin)
                                    val token = Prefs.getToken(ctx)
                                    ApiService.api.recoverySave(
                                        "Bearer $token",
                                        RecoverySaveRequest(
                                            wrapped_with_seed = w.wrappedB64,
                                            salt = "irm-rec/$uin",
                                            phrase_fingerprint = w.fingerprint
                                        )
                                    )
                                    Prefs.markRecoveryPhrase(ctx, true)
                                }.onSuccess { stage = GenStage.Done }
                                 .onFailure { error = "Не удалось сохранить: ${it.message}" }
                                loading = false
                            }
                        }
                    ) { Text(if (loading) "Сохранение…" else "Сохранить") }
                }
                GenStage.Done -> {
                    Text(
                        "Готово. Резервная фраза сохранена.\nДержите 12 слов в надёжном месте — без них восстановление невозможно.",
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
                }
            }
        }
    }
}

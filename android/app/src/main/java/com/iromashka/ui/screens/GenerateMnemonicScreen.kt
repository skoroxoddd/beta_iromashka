package com.iromashka.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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

private enum class GenStage { Intro, ShowPhrase, Confirm, Pin, Saving, Done }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateMnemonicScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uin = Prefs.getUin(ctx)
    val hasExisting = remember { Prefs.hasRecoveryPhrase(ctx) }

    var stage by rememberSaveable { mutableStateOf(GenStage.Intro) }
    var phrase by rememberSaveable { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRegenConfirm by remember { mutableStateOf(false) }

    // Choose 3 random word indices to confirm
    val confirmIdx = rememberSaveable(phrase) {
        if (phrase.isBlank()) listOf(0, 4, 8)
        else (0..11).shuffled().take(3).sorted()
    }
    val confirmInputs = remember(confirmIdx) { mutableStateListOf("", "", "") }

    fun doGenerate() {
        runCatching { Bip39.generate(ctx) }
            .onSuccess { phrase = it; stage = GenStage.ShowPhrase; error = null }
            .onFailure { error = it.message ?: "Ошибка генерации" }
    }

    suspend fun doSave() {
        loading = true; error = null; stage = GenStage.Saving
        runCatching {
            val wrappedPriv = Prefs.getWrappedPriv(ctx)
            require(wrappedPriv.isNotEmpty()) { "Локальный ключ не найден" }
            // Derive deterministic keypair from phrase
            val keyPair = Bip39.deriveKeyPair(ctx, phrase, uin)
            // Wrap new key with recovery phrase (for server backup)
            val w = Bip39.wrap(ctx, keyPair.private, phrase, uin)
            // Wrap new key with PIN (for local storage)
            val newWrapped = CryptoManager.wrapPrivateKey(keyPair.private, pin)
            val newPubKey = CryptoManager.exportPublicKey(keyPair.public)
            val token = Prefs.getToken(ctx)
            // Save recovery to server
            ApiService.api.recoverySave(
                "Bearer $token",
                RecoverySaveRequest(
                    wrapped_with_seed = w.wrappedB64,
                    salt = "irm-rec/$uin",
                    phrase_fingerprint = w.fingerprint
                )
            )
            // Update pubkey on server with deterministic key
            runCatching {
                ApiService.api.updatePubkey("Bearer $token",
                    com.iromashka.network.UpdatePubkeyRequest(newPubKey))
            }
            // Save PIN-wrapped key to server for cross-device PIN unlock
            runCatching {
                ApiService.api.saveUserKey("Bearer $token",
                    com.iromashka.network.SaveKeyRequest(newWrapped, ""))
            }
            // Update local storage with new deterministic key
            Prefs.updateWrappedPriv(ctx, newWrapped)
            Prefs.updatePubKey(ctx, newPubKey)
            Prefs.markRecoveryPhrase(ctx, true)
        }.onSuccess { stage = GenStage.Done; phrase = ""; pin = "" }
         .onFailure {
             error = "Не удалось сохранить: ${it.message}. Можно повторить."
             stage = GenStage.Pin
         }
        loading = false
    }

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
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = {
                            if (hasExisting) showRegenConfirm = true
                            else doGenerate()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (hasExisting) "Сгенерировать новую" else "Создать фразу") }
                }
                GenStage.ShowPhrase -> {
                    val words = phrase.split(' ')
                    Text("Запишите эти 12 слов по порядку:", style = MaterialTheme.typography.titleMedium)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            words.forEachIndexed { i, w ->
                                Text("${i + 1}. $w", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("recovery", phrase))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Скопировать в буфер")
                    }
                    Text(
                        "Никому не показывайте фразу. Кто её знает — может восстановить ваш аккаунт.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { stage = GenStage.Confirm }, modifier = Modifier.fillMaxWidth()) {
                        Text("Я записал, продолжить")
                    }
                }
                GenStage.Confirm -> {
                    val words = phrase.split(' ')
                    Text("Подтвердите, что записали фразу. Введите указанные слова:")
                    confirmIdx.forEachIndexed { idx, wordIdx ->
                        OutlinedTextField(
                            value = confirmInputs[idx],
                            onValueChange = { confirmInputs[idx] = it.lowercase().trim() },
                            label = { Text("Слово №${wordIdx + 1}") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val allOk = confirmIdx.withIndex().all { (i, wIdx) -> confirmInputs[i] == words[wIdx] }
                    if (confirmInputs.any { it.isNotBlank() } && !allOk) {
                        Text("Не совпадает", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { stage = GenStage.ShowPhrase; confirmInputs.fill("") },
                            modifier = Modifier.weight(1f)
                        ) { Text("Назад") }
                        Button(
                            enabled = allOk,
                            onClick = { stage = GenStage.Pin },
                            modifier = Modifier.weight(1f)
                        ) { Text("Далее") }
                    }
                }
                GenStage.Pin -> {
                    Text("Подтвердите свой текущий PIN, чтобы сохранить резервную копию.")
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !loading && pin.length >= 4,
                        onClick = { scope.launch { doSave() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить") }
                }
                GenStage.Saving -> {
                    Text("Сохранение…", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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

        if (showRegenConfirm) {
            AlertDialog(
                onDismissRequest = { showRegenConfirm = false },
                title = { Text("Заменить фразу?") },
                text = { Text("Старая фраза перестанет работать. Восстановиться по ней будет невозможно.") },
                confirmButton = {
                    TextButton(onClick = { showRegenConfirm = false; doGenerate() }) {
                        Text("Заменить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRegenConfirm = false }) { Text("Отмена") }
                }
            )
        }
    }
}

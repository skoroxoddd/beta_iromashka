package com.iromashka.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.google.gson.Gson
import com.iromashka.model.ThemeInfo
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.AuthViewModel
import java.util.zip.ZipInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val authVm = androidx.lifecycle.viewmodel.compose.viewModel<AuthViewModel>()
    var showChangePin by remember { mutableStateOf(false) }
    var showThemeManager by remember { mutableStateOf(false) }
    var installedThemes by remember { mutableStateOf(loadInstalledThemes(ctx)) }

    var pinOld by remember { mutableStateOf("") }
    var pinNew by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinLoading by remember { mutableStateOf(false) }

    val themePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importThemeFromZip(ctx, it) }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Text("Настройки", color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val uin = Prefs.getUin(ctx)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(56.dp).clip(CircleShape)
                                    .background(palette.accent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("UIN", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("UIN: $uin", fontWeight = FontWeight.SemiBold,
                                    fontSize = 18.sp, color = palette.textPrimary)
                                Text("АйРомашка v1.0", fontSize = 13.sp,
                                    color = palette.textSecondary)
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Темы оформления", fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, color = palette.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { themePicker.launch("application/zip") },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Установить .zip")
                            }
                            OutlinedButton(
                                onClick = { showThemeManager = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Управление")
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Безопасность", fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, color = palette.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showChangePin = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, null, tint = palette.accent)
                            Spacer(Modifier.width(12.dp))
                            Text("Сменить PIN", color = palette.textPrimary)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = palette.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("О приложении", fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp, color = palette.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text("АйРомашка", color = palette.textPrimary)
                        Text("E2E-шифрованный мессенджер",
                            fontSize = 13.sp, color = palette.textSecondary)
                        Text("P-256 + AES-256-GCM + HKDF-SHA256",
                            fontSize = 12.sp, color = palette.textSecondary)
                    }
                }
            }

            item {
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F)
                    )
                ) {
                    Icon(Icons.Default.ExitToApp, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Выйти")
                }
            }
        }
    }

    if (showChangePin) {
        AlertDialog(
            onDismissRequest = { showChangePin = false },
            containerColor = palette.surface,
            title = { Text("Сменить PIN", color = palette.textPrimary) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinOld,
                        onValueChange = { if (it.length <= 6) pinOld = it.filter { c -> c.isDigit() } },
                        label = { Text("Старый PIN") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinNew,
                        onValueChange = { if (it.length <= 6) pinNew = it.filter { c -> c.isDigit() } },
                        label = { Text("Новый PIN") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = { if (it.length <= 6) pinConfirm = it.filter { c -> c.isDigit() } },
                        label = { Text("Подтвердите") },
                        singleLine = true,
                        isError = pinConfirm.isNotEmpty() && pinConfirm != pinNew
                    )
                    pinError?.let {
                        Text(it, color = Color(0xFFD32F2F), fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pinNew.length < 6) {
                        pinError = "PIN должен быть 6 цифр"
                        return@TextButton
                    }
                    if (pinNew != pinConfirm) {
                        pinError = "PIN не совпадает"
                        return@TextButton
                    }
                    pinLoading = true
                    authVm.changePin(
                        oldPin = pinOld,
                        newPin = pinNew,
                        onSuccess = {
                            pinOld = ""; pinNew = ""; pinConfirm = ""; pinError = null
                            pinLoading = false; showChangePin = false
                        },
                        onError = { err -> pinError = err; pinLoading = false }
                    )
                }) {
                    if (pinLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.textPrimary)
                    } else {
                        Text("Сменить")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showChangePin = false }) { Text("Отмена") } }
        )
    }

    if (showThemeManager) {
        ThemeManagerDialog(
            themes = installedThemes,
            onDismiss = { showThemeManager = false },
            onThemeSelected = { /* apply theme */ }
        )
    }
}

@Composable
private fun ThemeManagerDialog(
    themes: List<ThemeInfo>,
    onDismiss: () -> Unit,
    onThemeSelected: (ThemeInfo) -> Unit
) {
    val palette = LocalThemePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Установленные темы", color = palette.textPrimary) },
        text = {
            if (themes.isEmpty()) {
                Text("Нет установленных тем", color = palette.textSecondary)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(themes) { theme ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onThemeSelected(theme) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                                    .background(Color(theme.primaryColor)),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(theme.name, fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary)
                                Text("v${theme.version} — ${theme.author}",
                                    fontSize = 12.sp, color = palette.textSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private fun importThemeFromZip(ctx: android.content.Context, uri: Uri) {
    runCatching {
        val inputStream = ctx.contentResolver.openInputStream(uri) ?: return@runCatching
        val zipInput = ZipInputStream(inputStream)
        var manifestJson: String? = null
        zipInput.use { zis ->
            var entry: java.util.zip.ZipEntry?
            while (zis.nextEntry.also { entry = it } != null) {
                if (entry?.name == "theme.json") {
                    manifestJson = zis.readAllBytes().decodeToString()
                }
                zis.closeEntry()
            }
        }
        manifestJson?.let { json ->
            val themeInfo = Gson().fromJson(json, ThemeInfo::class.java)
            saveThemeInfo(ctx, themeInfo)
        }
    }
}

private fun saveThemeInfo(ctx: android.content.Context, theme: ThemeInfo) {
    val prefs = ctx.getSharedPreferences("themes", android.content.Context.MODE_PRIVATE)
    val json = Gson().toJson(theme)
    prefs.edit().putString("theme_${theme.name}", json).apply()
}

private fun loadInstalledThemes(ctx: android.content.Context): List<ThemeInfo> {
    val prefs = ctx.getSharedPreferences("themes", android.content.Context.MODE_PRIVATE)
    return prefs.all.values.mapNotNull { value ->
        runCatching { Gson().fromJson(value as String, ThemeInfo::class.java) }.getOrNull()
    }
}

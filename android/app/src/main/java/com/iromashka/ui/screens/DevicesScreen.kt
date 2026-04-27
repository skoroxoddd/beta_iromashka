package com.iromashka.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iromashka.network.ApiService
import com.iromashka.network.DeviceInfoResponse
import com.iromashka.storage.Prefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var devices by remember { mutableStateOf<List<DeviceInfoResponse>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val myUin = Prefs.getUin(ctx)
    val token = Prefs.getToken(ctx)
    val myDevId = Prefs.getDeviceId(ctx)
    val scope = rememberCoroutineScope()

    suspend fun doReload() {
        loading = true; error = null
        runCatching {
            ApiService.api.getUserDevices("Bearer $token", myUin)
        }.onSuccess { devices = it }
         .onFailure { error = it.message }
        loading = false
    }

    LaunchedEffect(Unit) { doReload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Активные сессии") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                error != null -> Text("Ошибка: $error", Modifier.align(Alignment.Center))
                devices.isEmpty() -> Text("Нет других сессий", Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(devices) { d ->
                        val isMe = d.device_id == myDevId
                        ListItem(
                            headlineContent = { Text(if (d.device_name.isBlank()) "Устройство" else d.device_name) },
                            supportingContent = {
                                Column {
                                    Text("ID: ${d.device_id.take(8)}…", style = MaterialTheme.typography.bodySmall)
                                    Text("Last seen: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(d.last_seen))}",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (isMe) Text("(это устройство)", color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            },
                            trailingContent = {
                                if (!isMe) {
                                    var revoking by remember { mutableStateOf(false) }
                                    IconButton(onClick = {
                                        revoking = true
                                        scope.launch {
                                            runCatching {
                                                ApiService.api.deleteDevice("Bearer $token", d.device_id)
                                            }
                                            doReload()
                                            revoking = false
                                        }
                                    }, enabled = !revoking) {
                                        Icon(Icons.Default.Delete, "Revoke", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

package com.iromashka.ui.screens

import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.iromashka.model.DiscoveredContactItem
import com.iromashka.model.GroupItem
import com.iromashka.model.ThemeInfo
import com.iromashka.storage.ContactEntity
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    myUin: Long,
    myNickname: String,
    viewModel: ChatViewModel,
    onChatOpen: (Long, String) -> Unit,
    onGroupChatOpen: (Long, String) -> Unit,
    onLogout: () -> Unit,
    onPhoneLookup: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val palette = LocalThemePalette.current
    val contacts by viewModel.getContacts().collectAsState(initial = emptyList())
    val statuses by viewModel.statuses.collectAsState()

    var menuExpanded by remember { mutableStateOf(false) }
    var showAddContact by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadGroups() }

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
                IconButton(onClick = { showAddContact = true }) {
                    Icon(Icons.Default.Add, null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(myNickname, color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("UIN: $myUin", color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, null, tint = Color.White)
                    }
                    DropdownMenu(expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = palette.surface) {
                        DropdownMenuItem(
                            text = { Text("Добавить контакт по UIN") },
                            onClick = { menuExpanded = false; showAddContact = true },
                            leadingIcon = { Icon(Icons.Default.Add, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Поиск по телефонной книге") },
                            onClick = { menuExpanded = false; onPhoneLookup() },
                            leadingIcon = { Icon(Icons.Default.Phone, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Настройки") },
                            onClick = { menuExpanded = false; onSettings() },
                            leadingIcon = { Icon(Icons.Default.Settings, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Выйти") },
                            onClick = { menuExpanded = false; onLogout() },
                            leadingIcon = { Icon(Icons.Default.ExitToApp, null) }
                        )
                    }
                }
            }
        }

        if (contacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PersonSearch, null,
                        tint = palette.textSecondary, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Нет чатов", color = palette.textSecondary, fontSize = 16.sp)
                    Text("Нажмите + чтобы добавить контакт",
                        color = palette.textSecondary, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            LazyColumn {
                items(contacts, key = { it.uin }) { contact ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onChatOpen(contact.uin, contact.nickname) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(palette.accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                contact.nickname.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold, fontSize = 20.sp
                            )
                        }
                        val status = statuses[contact.uin] ?: "Offline"
                        val statusColor = when (status) {
                            "Online" -> Color(0xFF4CAF50)
                            "Away" -> Color(0xFFFFC107)
                            "Invisible" -> Color(0xFF9E9E9E)
                            else -> Color(0xFF9E9E9E)
                        }
                        Box(
                            modifier = Modifier.size(14.dp)
                                .offset(x = (-10).dp, y = (-18).dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(contact.nickname, fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp, color = palette.textPrimary)
                            Text(status, fontSize = 12.sp, color = statusColor.copy(alpha = 0.8f))
                        }
                    }
                    HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
                }
            }
        }
    }

    if (showAddContact) {
        AddContactDialog(
            onDismiss = { showAddContact = false },
            onAdd = { uin, nick ->
                showAddContact = false
                viewModel.addContact(uin, nick)
                onChatOpen(uin, nick)
            }
        )
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, String) -> Unit
) {
    val palette = LocalThemePalette.current
    var uinInput by remember { mutableStateOf("") }
    var nickInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Добавить контакт", color = palette.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = uinInput,
                    onValueChange = { uinInput = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("UIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = { nickInput = it },
                    label = { Text("Имя (необязательно)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val uinStr = uinInput.filter { it.isDigit() }
                    val uin = uinStr.toLongOrNull()
                    if (uin != null) {
                        val nick = nickInput.ifBlank { "UIN $uin" }
                        onAdd(uin, nick)
                    }
                },
                enabled = uinInput.filter { it.isDigit() }.toLongOrNull() != null
            ) { Text("Открыть чат") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

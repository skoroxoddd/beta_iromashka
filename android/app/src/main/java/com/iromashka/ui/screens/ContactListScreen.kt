package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import com.iromashka.R
import com.iromashka.storage.ContactEntity
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.ui.theme.ThemeMode
import com.iromashka.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    myUin: Long,
    myNickname: String,
    viewModel: ChatViewModel,
    onChatOpen: (Long, String) -> Unit,
    onGroupChatOpen: (Long, String) -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val contacts by viewModel.contacts.collectAsState(initial = emptyList())
    val groups by viewModel.groups.collectAsState()
    val wsConnected by viewModel.wsConnected.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDiscoverDialog by remember { mutableStateOf(false) }
    var showPinChange by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var tabSelected by remember { mutableStateOf(0) } // 0=contacts, 1=groups

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        // Title bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.titleBar)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (wsConnected) palette.onlineGreen else palette.offlineGray)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(myNickname, color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("UIN: $myUin", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.White)
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        shadowElevation = 8.dp,
                        color = palette.surface
                    ) {
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Изменить статус") },
                            onClick = { menuExpanded = false; showStatusDialog = true },
                            leadingIcon = { Icon(Icons.Default.Mood, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Сменить тему") },
                            onClick = { menuExpanded = false; showThemeDialog = true },
                            leadingIcon = { Icon(Icons.Default.Palette, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Сменить PIN") },
                            onClick = { menuExpanded = false; showPinChange = true },
                            leadingIcon = { Icon(Icons.Default.Lock, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Выйти") },
                            onClick = { menuExpanded = false; onLogout() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null) }
                        )
                        }
                    }
                }
            }
        }

        // Tabs: Чаты / Группы
        TabRow(selectedTabIndex = tabSelected, containerColor = palette.background,
            contentColor = palette.accent) {
            Tab(selected = tabSelected == 0, onClick = { tabSelected = 0 }) {
                Text("Контакты", Modifier.padding(vertical = 8.dp))
            }
            Tab(selected = tabSelected == 1, onClick = { tabSelected = 1 }) {
                Text("Группы", Modifier.padding(vertical = 8.dp))
            }
        }

        when (tabSelected) {
            0 -> ContactTab(contacts, palette,
                onAddClick = { showAddDialog = true },
                onChatOpen = onChatOpen
            )
            1 -> GroupTab(groups, palette,
                onCreateClick = { showCreateGroupDialog = true },
                onGroupClick = onGroupChatOpen
            )
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { uin, nick ->
                viewModel.addContact(uin, nick)
                showAddDialog = false
            },
            palette = palette,
            onDiscover = {
                showAddDialog = false;
                showDiscoverDialog = true
            }
        )
    }

    if (showDiscoverDialog) {
        PhoneDiscoverDialog(
            onDismiss = { showDiscoverDialog = false },
            onAdd = { uin, nick ->
                viewModel.addContact(uin, nick)
                showDiscoverDialog = false
            },
            viewModel = viewModel,
            palette = palette
        )
    }

    if (showCreateGroupDialog) {
        CreateGroupDialog(
            onDismiss = { showCreateGroupDialog = false },
            onCreate = { name, memberUins ->
                viewModel.createGroup(name, memberUins)
                showCreateGroupDialog = false
            },
            palette = palette
        )
    }

    if (showPinChange) {
        PinChangeDialog(
            viewModel = viewModel,
            onDismiss = { showPinChange = false },
            onSuccess = { showPinChange = false }
        )
    }

    if (showStatusDialog) {
        StatusPickerDialog(
            current = viewModel.myStatus.collectAsState().value,
            onSelected = { viewModel.setMyStatus(it) },
            onDismiss = { showStatusDialog = false },
            palette = palette
        )
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            current = Prefs.getTheme(ctx),
            onSelected = { Prefs.setTheme(ctx, it) },
            onDismiss = { showThemeDialog = false },
            palette = palette
        )
    }
}

@Composable
private fun ContactTab(
    contacts: List<ContactEntity>,
    palette: com.iromashka.ui.theme.ThemePalette,
    onAddClick: () -> Unit,
    onChatOpen: (Long, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Контакты (${contacts.size})", fontSize = 12.sp, color = palette.textSecondary)
        IconButton(onClick = onAddClick, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.PersonAdd, null, tint = palette.accent, modifier = Modifier.size(20.dp))
        }
    }

    HorizontalDivider(color = palette.divider)

    if (contacts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "iRomashka",
                    modifier = Modifier.size(120.dp).clip(CircleShape)
                )
                Spacer(Modifier.height(16.dp))
                Text("iRomashka", color = palette.accent,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Список контактов пуст", color = palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onAddClick) {
                    Text("Добавить контакт", color = palette.accent)
                }
            }
        }
    } else {
        LazyColumn {
            items(contacts, key = { it.uin }) { contact ->
                ContactItem(contact, palette) { onChatOpen(contact.uin, contact.nickname) }
                HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupTab(
    groups: List<ChatViewModel.GroupItemDb>,
    palette: com.iromashka.ui.theme.ThemePalette,
    onCreateClick: () -> Unit,
    onGroupClick: (Long, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Группы (${groups.size})", fontSize = 12.sp, color = palette.textSecondary)
        IconButton(onClick = onCreateClick, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.GroupAdd, null, tint = palette.accent, modifier = Modifier.size(20.dp))
        }
    }

    HorizontalDivider(color = palette.divider)

    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Нет групп", color = palette.textSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCreateClick) {
                    Text("Создать группу", color = palette.accent)
                }
            }
        }
    } else {
        LazyColumn {
            items(groups, key = { it.id }) { group ->
                GroupItemRow(group, palette) { onGroupClick(group.id, group.name) }
                HorizontalDivider(color = palette.divider, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun GroupItemRow(
    group: ChatViewModel.GroupItemDb,
    palette: com.iromashka.ui.theme.ThemePalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(palette.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Group, null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(group.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = palette.textPrimary)
            Text("ID: ${group.id}", fontSize = 11.sp, color = palette.textSecondary)
        }
    }
}

@Composable
private fun ContactItem(contact: ContactEntity, palette: com.iromashka.ui.theme.ThemePalette, onClick: () -> Unit) {
    val statusColor = when (contact.status) {
        "Online" -> palette.onlineGreen
        "Away"   -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else     -> palette.offlineGray
    }
    val statusLabel = when (contact.status) {
        "Online"    -> "в сети"
        "Away"      -> "отошёл"
        else        -> "не в сети"
    }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.nickname.take(1).uppercase(),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold, fontSize = 18.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(contact.nickname, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = palette.textPrimary)
            Text("UIN: ${contact.uin} · $statusLabel", fontSize = 11.sp, color = palette.textSecondary)
        }
    }
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, String) -> Unit,
    palette: com.iromashka.ui.theme.ThemePalette,
    onDiscover: () -> Unit
) {
    var uinText by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Добавить контакт", color = palette.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = uinText,
                    onValueChange = { uinText = it.filter { c -> c.isDigit() } },
                    label = { Text("UIN", color = palette.textSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Имя", color = palette.textSecondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.accent,
                        unfocusedBorderColor = palette.divider,
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDiscover) { Text("По номеру") }
                TextButton(
                    onClick = {
                        val uin = uinText.toLongOrNull() ?: return@TextButton
                        if (nickname.isBlank()) return@TextButton
                        onAdd(uin, nickname)
                    },
                    enabled = uinText.toLongOrNull() != null && nickname.isNotBlank()
                ) { Text("По UIN") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun PhoneDiscoverDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, String) -> Unit,
    viewModel: ChatViewModel,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    var phoneText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChatViewModel.DiscoveredResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Найти по номеру", color = palette.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = phoneText,
                    onValueChange = { phoneText = it },
                    label = { Text("Номер телефона", color = palette.textSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (searching) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (results.isNotEmpty()) {
                    results.forEach { contact ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onAdd(contact.uin, contact.nickname)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${contact.nickname} (${contact.uin})", 
                                color = palette.textPrimary, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.Add, null, tint = palette.accent)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                searching = true
                viewModel.discoverContacts(listOf(phoneText)) { results = it; searching = false }
            }) { Text("Найти") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun CreateGroupDialog(
    onDismiss: () -> Unit,
    onCreate: (String, List<Long>) -> Unit,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    var name by remember { mutableStateOf("") }
    var memberUins by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Создать группу", color = palette.textPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название группы", color = palette.textSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = memberUins,
                onValueChange = { memberUins = it.filter { c -> c.isDigit() || c == ' ' || c == ',' } },
                label = { Text("UIN через запятую", color = palette.textSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val uins = memberUins.split(Regex("[, ]+"))
                        .mapNotNull { it.trim().toLongOrNull() }
                    onCreate(name, uins)
                },
                enabled = name.isNotBlank()
            ) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ThemePickerDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Выберите тему", color = palette.textPrimary) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    val isSelected = mode.name == current
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onSelected(mode.name) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = { onSelected(mode.name) })
                        Spacer(Modifier.width(12.dp))
                        val label = if (mode == ThemeMode.Light) "Светлая" else "Тёмная"
                        Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = palette.textPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun PinChangeDialog(
    @Suppress("UNUSED_PARAMETER") viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val authVm: com.iromashka.viewmodel.AuthViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                OutlinedTextField(value = oldPin, onValueChange = { oldPin = it },
                    label = { Text("Старый PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                OutlinedTextField(value = newPin, onValueChange = { newPin = it },
                    label = { Text("Новый PIN (мин. 6 цифр)") },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newPin.length < 6) { error = "PIN минимум 6 цифр"; return@TextButton }
                authVm.changePin(oldPin, newPin,
                    onSuccess = { onSuccess() },
                    onError = { msg -> error = msg })
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun StatusPickerDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    val statuses = listOf(
        "Online"      to "В сети",
        "Away"        to "Отошёл",
        "Eating"      to "Ем",
        "Working"     to "Работаю",
        "Sleeping"    to "Сплю",
        "OnRoad"      to "В дороге",
        "Unavailable" to "Недоступен",
        "Invisible"   to "Невидим"
    )
    val colors = mapOf(
        "Online" to androidx.compose.ui.graphics.Color(0xFF0AC630),
        "Away" to androidx.compose.ui.graphics.Color(0xFFF59E0B),
        "Eating" to androidx.compose.ui.graphics.Color(0xFFFF7043),
        "Working" to androidx.compose.ui.graphics.Color(0xFF7E57C2),
        "Sleeping" to androidx.compose.ui.graphics.Color(0xFF5C6BC0),
        "OnRoad" to androidx.compose.ui.graphics.Color(0xFF26A69A),
        "Unavailable" to androidx.compose.ui.graphics.Color(0xFFEF5350),
        "Invisible" to androidx.compose.ui.graphics.Color(0xFF90A4AE)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Мой статус", color = palette.textPrimary) },
        text = {
            Column {
                statuses.forEach { (key, label) ->
                    val selected = key == current
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            onSelected(key); onDismiss()
                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(12.dp).clip(CircleShape)
                                .background(colors[key] ?: palette.offlineGray)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            label,
                            color = palette.textPrimary,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

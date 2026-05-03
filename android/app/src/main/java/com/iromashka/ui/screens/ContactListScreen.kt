package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    myUin: Long,
    myNickname: String,
    viewModel: ChatViewModel,
    onChatOpen: (Long, String) -> Unit,
    onGroupChatOpen: (Long, String) -> Unit,
    onDevices: () -> Unit = {},
    onRecoveryGenerate: () -> Unit = {},
    onLogout: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val contacts by viewModel.contacts.collectAsState(initial = emptyList())
    val groups by viewModel.groups.collectAsState()
    val wsConnected by viewModel.wsConnected.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var contactMenuTarget by remember { mutableStateOf<ContactEntity?>(null) }
    var renameTarget by remember { mutableStateOf<ContactEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<ContactEntity?>(null) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDiscoverDialog by remember { mutableStateOf(false) }
    var showPinChange by remember { mutableStateOf(false) }
    var showResetIdentity by remember { mutableStateOf(false) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var tabSelected by remember { mutableStateOf(0) } // 0=contacts, 1=groups

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.syncPhoneContacts()
    }

    LaunchedEffect(Unit) {
        val perm = android.Manifest.permission.READ_CONTACTS
        if (ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            viewModel.syncPhoneContacts()
        } else {
            contactsPermission.launch(perm)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.systemBars)
    ) {
        // Title bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.titleBar)
                .padding(horizontal = 8.dp, vertical = 8.dp)
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
                            text = { Text("Активные сессии") },
                            onClick = { menuExpanded = false; onDevices() },
                            leadingIcon = { Icon(Icons.Default.Devices, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Резервная фраза") },
                            onClick = { menuExpanded = false; onRecoveryGenerate() },
                            leadingIcon = { Icon(Icons.Default.Lock, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Сбросить identity") },
                            onClick = { menuExpanded = false; showResetIdentity = true },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) }
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
                onChatOpen = onChatOpen,
                onContactLongClick = { contactMenuTarget = it }
            )
            1 -> GroupTab(groups, palette,
                onCreateClick = { showCreateGroupDialog = true },
                onGroupClick = onGroupChatOpen
            )
        }
    }

    contactMenuTarget?.let { target ->
        ContactActionsSheet(
            contact = target,
            palette = palette,
            onDismiss = { contactMenuTarget = null },
            onRename = { renameTarget = target; contactMenuTarget = null },
            onDelete = { deleteTarget = target; contactMenuTarget = null },
        )
    }

    renameTarget?.let { target ->
        RenameContactDialog(
            contact = target,
            palette = palette,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameContact(target.uin, newName)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = palette.surface,
            title = { Text("Удалить контакт?", color = palette.textPrimary) },
            text = {
                Text(
                    "${target.nickname} (UIN ${target.uin}) будет удалён из списка. " +
                        "Переписка не удаляется.",
                    color = palette.textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContact(target.uin)
                    deleteTarget = null
                }) { Text("Удалить", color = palette.errorRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { uin, nick ->
                viewModel.addContact(uin, nick)
                showAddDialog = false
            },
            onValidate = { uin -> viewModel.validateUinExists(uin) },
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

    if (showResetIdentity) {
        ResetIdentityDialog(
            viewModel = viewModel,
            onDismiss = { showResetIdentity = false },
            onSuccess = { showResetIdentity = false; onLogout() }
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
    onChatOpen: (Long, String) -> Unit,
    onContactLongClick: (ContactEntity) -> Unit
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
                ContactItem(
                    contact = contact,
                    palette = palette,
                    onClick = { onChatOpen(contact.uin, contact.nickname) },
                    onLongClick = { onContactLongClick(contact) }
                )
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
private fun ContactItem(
    contact: ContactEntity,
    palette: com.iromashka.ui.theme.ThemePalette,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val statusColor = when (contact.status) {
        "Online"      -> palette.onlineGreen
        "Away"        -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        "Eating"      -> androidx.compose.ui.graphics.Color(0xFFFF7043)
        "Working"     -> androidx.compose.ui.graphics.Color(0xFF7E57C2)
        "Sleeping"    -> androidx.compose.ui.graphics.Color(0xFF5C6BC0)
        "OnRoad"      -> androidx.compose.ui.graphics.Color(0xFF26A69A)
        "Unavailable" -> androidx.compose.ui.graphics.Color(0xFFEF5350)
        "Invisible", "Offline", "" -> palette.offlineGray
        else          -> palette.offlineGray
    }
    val statusLabel = when (contact.status) {
        "Online"      -> "в сети"
        "Away"        -> "отошёл"
        "Eating"      -> "ем"
        "Working"     -> "работаю"
        "Sleeping"    -> "сплю"
        "OnRoad"      -> "в дороге"
        "Unavailable" -> "недоступен"
        "Invisible", "Offline", "" -> "не в сети"
        else          -> "не в сети"
    }
    // Deterministic avatar background from UIN
    val avatarColors = listOf(
        0xFF27AE60, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63,
        0xFFFF5722, 0xFF009688, 0xFF3F51B5, 0xFF795548
    )
    val avatarBg = androidx.compose.ui.graphics.Color(avatarColors[(contact.uin % avatarColors.size).toInt()])

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(50.dp)) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(avatarBg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.nickname.take(1).uppercase(),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.nickname,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = palette.textPrimary,
                maxLines = 1
            )
            Text(
                statusLabel,
                fontSize = 13.5.sp,
                color = palette.textSecondary,
                maxLines = 1
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ContactActionsSheet(
    contact: ContactEntity,
    palette: com.iromashka.ui.theme.ThemePalette,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                contact.nickname,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                color = palette.textPrimary,
            )
            HorizontalDivider(color = palette.divider)
            Text(
                "Переименовать",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRename)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                color = palette.textPrimary,
                fontSize = 15.sp,
            )
            Text(
                "Удалить контакт",
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                color = palette.errorRed,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun RenameContactDialog(
    contact: ContactEntity,
    palette: com.iromashka.ui.theme.ThemePalette,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(contact.uin) { mutableStateOf(contact.nickname) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Переименовать", color = palette.textPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Новое имя", color = palette.textSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.divider,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != contact.nickname,
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (Long, String) -> Unit,
    onValidate: suspend (Long) -> Boolean?,
    palette: com.iromashka.ui.theme.ThemePalette,
    onDiscover: () -> Unit
) {
    var uinText by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Добавить контакт", color = palette.textPrimary) },
        text = {
            Column {
                OutlinedTextField(
                    value = uinText,
                    onValueChange = { uinText = it.filter { c -> c.isDigit() }; errorText = null },
                    label = { Text("UIN", color = palette.textSecondary) },
                    singleLine = true,
                    isError = errorText != null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (errorText != null) palette.errorRed else palette.accent,
                        unfocusedBorderColor = if (errorText != null) palette.errorRed else palette.divider,
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
                if (errorText != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(errorText!!, color = palette.errorRed, fontSize = 12.sp)
                }
                if (validating) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDiscover, enabled = !validating) { Text("По номеру") }
                TextButton(
                    onClick = {
                        val uin = uinText.toLongOrNull() ?: return@TextButton
                        if (nickname.isBlank()) return@TextButton
                        validating = true
                        errorText = null
                        scope.launch {
                            val exists = onValidate(uin)
                            validating = false
                            when (exists) {
                                true -> onAdd(uin, nickname)
                                false -> errorText = "UIN $uin не найден"
                                null -> errorText = "Нет связи с сервером, попробуйте позже"
                            }
                        }
                    },
                    enabled = !validating && uinText.toLongOrNull() != null && nickname.isNotBlank()
                ) { Text("По UIN") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !validating) { Text("Отмена") } }
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
            Column(modifier = Modifier.heightIn(max = 300.dp)) {
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
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(results) { contact ->
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


@Composable
private fun ResetIdentityDialog(
    viewModel: com.iromashka.viewmodel.ChatViewModel,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сбросить identity") },
        text = {
            Column {
                if (!confirmed) {
                    Text(
                        "⚠️ ВНИМАНИЕ. Эта операция:\n" +
                        "• Удалит навсегда всю переписку — её невозможно будет прочитать\n" +
                        "• Сделает резервную фразу старого ключа бесполезной\n" +
                        "• Сгенерирует новый ключ; собеседники получат уведомление\n\n" +
                        "Если вы здесь потому что забыли PIN — отмените и используйте «Восстановить по фразе» на экране входа.\n\n" +
                        "Продолжить только если действительно хотите начать заново."
                    )
                } else {
                    Text("Введите текущий PIN для подтверждения:")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("PIN") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = androidx.compose.ui.graphics.Color.Red, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && (!confirmed || pin.length >= 4),
                onClick = {
                    if (!confirmed) { confirmed = true; return@TextButton }
                    loading = true
                    error = null
                    viewModel.resetIdentity(pin) { ok, err ->
                        loading = false
                        if (ok) onSuccess()
                        else error = err ?: "Не удалось сбросить"
                    }
                }
            ) { Text(if (!confirmed) "Дальше" else if (loading) "..." else "Сбросить", color = androidx.compose.ui.graphics.Color.Red) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

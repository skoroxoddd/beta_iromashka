package com.iromashka.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: Long,
    groupName: String,
    viewModel: com.iromashka.viewmodel.ChatViewModel,
    onBack: () -> Unit
) {
    val palette = com.iromashka.ui.theme.LocalThemePalette.current
    val myUin = com.iromashka.storage.Prefs.getUin(
        androidx.compose.ui.platform.LocalContext.current
    )

    var inputText by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    // Placeholder messages — group messaging needs server WS routing
    // When server broadcasts group messages via WS, these will be populated
    val messages by viewModel.getGroupMessages(groupId).collectAsState(initial = emptyList())
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.chatBg)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
    ) {
        // ── Title bar ───────────────────────────────────
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
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null,
                        tint = androidx.compose.ui.graphics.Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(groupName, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color.White)
                    Text("Группа #$groupId", fontSize = 11.sp,
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.PersonAdd, null,
                        tint = androidx.compose.ui.graphics.Color.White)
                }
            }
        }

        HorizontalDivider(color = palette.divider, thickness = 0.5.dp)

        // ── Messages ────────────────────────────────────
        if (messages.isEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Group, null,
                        tint = palette.textSecondary,
                        modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Нет сообщений",
                        color = palette.textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("Группы используют сквозное шифрование",
                        color = palette.textSecondary, fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    val isOutgoing = msg.senderUin == myUin
                    MessageBubbleGroup(msg, isOutgoing, palette)
                }
            }
        }

        // ── Input ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Сообщение...", color = palette.textSecondary) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.inputBg,
                    unfocusedContainerColor = palette.inputBg,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
                maxLines = 4,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendGroupMessage(groupId, inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Отправить",
                    tint = if (inputText.isNotBlank()) palette.accent else palette.textSecondary)
            }
        }
    }

    if (showAddDialog) {
        AddMemberDialog(
            groupId = groupId,
            viewModel = viewModel,
            onDismiss = { showAddDialog = false },
            palette = palette
        )
    }
}

@Composable
private fun MessageBubbleGroup(
    msg: com.iromashka.viewmodel.ChatMessage,
    isOutgoing: Boolean,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) androidx.compose.foundation.layout.Arrangement.End
        else androidx.compose.foundation.layout.Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isOutgoing) palette.bubbleOut else palette.bubbleIn)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Sender name
            if (!isOutgoing) {
                Text(msg.senderNickname, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.accent)
                Spacer(Modifier.height(2.dp))
            }
            Text(msg.text, fontSize = 14.sp,
                color = if (isOutgoing) androidx.compose.ui.graphics.Color.White
                else palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            val timeStr = msg.formattedTime()
            Text(timeStr, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
                color = if (isOutgoing) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                else palette.textSecondary)
        }
    }
}

@Composable
private fun AddMemberDialog(
    groupId: Long,
    viewModel: com.iromashka.viewmodel.ChatViewModel,
    onDismiss: () -> Unit,
    palette: com.iromashka.ui.theme.ThemePalette
) {
    var uinText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text("Добавить участника", color = palette.textPrimary) },
        text = {
            OutlinedTextField(
                value = uinText,
                onValueChange = { uinText = it.filter { c -> c.isDigit() } },
                label = { Text("UIN пользователя", color = palette.textSecondary) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val uin = uinText.toLongOrNull() ?: return@TextButton
                    viewModel.addGroupMember(groupId, uin)
                    onDismiss()
                },
                enabled = uinText.toLongOrNull() != null
            ) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

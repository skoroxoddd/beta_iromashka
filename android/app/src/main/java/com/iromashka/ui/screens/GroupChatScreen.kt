package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PersonAdd
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
import com.iromashka.storage.Prefs
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatScreen(
    groupId: Long,
    groupName: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val myUin = Prefs.getUin(ctx)

    var inputText by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier.fillMaxSize().background(palette.chatBg)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Brush.horizontalGradient(palette.titleBar))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(groupName, fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = Color.White)
                    Text("Группа #$groupId", fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.PersonAdd, null, tint = Color.White)
                }
            }
        }

        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Group, null,
                    tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("Групповой чат", color = palette.textSecondary, fontSize = 14.sp)
                Text("E2E-шифрование для каждого участника",
                    color = palette.textSecondary, fontSize = 11.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(palette.surface).padding(8.dp),
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
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                maxLines = 4,
            )
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendGroupMessage(groupId, inputText.trim())
                        playSound(ctx, "outgoing")
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
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
private fun AddMemberDialog(
    groupId: Long,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    var uinText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить участника") },
        text = {
            OutlinedTextField(
                value = uinText,
                onValueChange = { uinText = it.filter { c -> c.isDigit() } },
                label = { Text("UIN пользователя") },
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

private fun playSound(ctx: android.content.Context, type: String) {
    val resId = when (type) {
        "outgoing" -> com.iromashka.R.raw.outgoing_message
        "incoming" -> com.iromashka.R.raw.new_message
        else -> com.iromashka.R.raw.new_message
    }
    if (resId != 0) {
        runCatching {
            val mp = MediaPlayer.create(ctx, resId)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        }
    }
}

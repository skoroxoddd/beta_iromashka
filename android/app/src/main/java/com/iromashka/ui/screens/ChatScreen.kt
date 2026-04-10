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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.viewmodel.ChatViewModel
import com.iromashka.viewmodel.UIMessage
import com.iromashka.viewmodel.MessageStatus
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    myUin: Long,
    toUin: Long,
    toNickname: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val palette = LocalThemePalette.current
    val ctx = LocalContext.current
    val messages by viewModel.messagesState.collectAsState()
    val wsConnected by viewModel.wsConnected.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isUserTyping by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    var lastCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > lastCount) {
            val incoming = messages.takeLast(messages.size - lastCount).any { !it.isOutgoing }
            if (incoming) playSound(ctx, "incoming")
        }
        lastCount = messages.size
    }

    Column(
        modifier = Modifier.fillMaxSize().background(palette.chatBg)
    ) {
        // Title bar
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
                    Text(toNickname, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(8.dp).clip(CircleShape)
                                .background(if (wsConnected) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("UIN: $toUin", fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, palette)
            }
        }

        if (isUserTyping) {
            Text("печатает...",
                modifier = Modifier.fillMaxWidth()
                    .background(palette.surface.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 12.sp, fontStyle = FontStyle.Italic,
                color = palette.textSecondary)
        }

        // Input
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(palette.surface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it
                    if (inputText.isNotEmpty() && !isUserTyping) {
                        isUserTyping = true
                        viewModel.sendTyping(toUin, true)
                    } else if (inputText.isEmpty() && isUserTyping) {
                        isUserTyping = false
                        viewModel.sendTyping(toUin, false)
                    }
                },
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
                        viewModel.sendMessage(toUin, inputText.trim())
                        isUserTyping = false
                        viewModel.sendTyping(toUin, false)
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
}

@Composable
private fun MessageBubble(msg: UIMessage, palette: com.iromashka.ui.theme.ThemePalette) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (msg.isOutgoing) palette.bubbleOut else palette.bubbleIn)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(msg.text, fontSize = 14.sp,
                color = if (msg.isOutgoing) Color.White else palette.textPrimary)
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(formatTime(msg.timestamp), fontSize = 10.sp,
                    color = if (msg.isOutgoing) Color.White.copy(alpha = 0.6f) else palette.textSecondary)
                if (msg.isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (msg.status) {
                            MessageStatus.Read -> "✓✓"
                            MessageStatus.Delivered -> "✓✓"
                            MessageStatus.Sent -> "✓"
                            else -> "✓"
                        },
                        fontSize = 10.sp,
                        color = when (msg.status) {
                            MessageStatus.Read -> Color(0xFF4FC3F7)
                            else -> Color.White.copy(alpha = 0.6f)
                        }
                    )
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format("%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

private fun playSound(ctx: android.content.Context, type: String) {
    val resId = when (type) {
        "outgoing" -> com.iromashka.R.raw.outgoing_message
        "incoming" -> com.iromashka.R.raw.new_message
        "online" -> com.iromashka.R.raw.online_notify
        "error" -> com.iromashka.R.raw.error_sound
        "logout" -> com.iromashka.R.raw.logout_sound
        else -> com.iromashka.R.raw.new_message
    }
    if (resId != 0) try {
        val mp = MediaPlayer.create(ctx, resId)
        mp?.setOnCompletionListener { it.release() }
        mp?.start()
    } catch (_: Exception) { /* ignore */ }
}

package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.core.content.res.ResourcesCompat
import com.iromashka.ui.theme.LocalThemePalette
import com.iromashka.ui.theme.ThemePalette
import com.iromashka.viewmodel.ChatViewModel
import com.iromashka.viewmodel.MessageStatus

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
    val typingUsers by viewModel.typingUsers.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var isUserTyping by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Sound on incoming message
    var lastCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > lastCount) {
            val newMsgs = messages.drop(lastCount)
            val hasIncoming = newMsgs.any { !it.isOutgoing }
            if (!hasIncoming) playSound(ctx, "incoming")
        }
        lastCount = messages.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.chatBg)
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
                    Text(toNickname, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = androidx.compose.ui.graphics.Color.White)
                    // Typing indicator or online status
                    val typingList = typingUsers.filter { it != myUin && it != toUin }
                    if (typingList.contains(toUin)) {
                        Text("печатает...", fontSize = 11.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = if (wsConnected) palette.onlineGreen else palette.offlineGray
                            Box(modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(dotColor))
                            Spacer(Modifier.width(4.dp))
                            Text("UIN: $toUin", fontSize = 11.sp,
                                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = palette.divider, thickness = 0.5.dp)

        // ── Messages ────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            reverseLayout = false,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages, key = { it.messageId }) { msg ->
                val isOutgoing = msg.status != null
                MessageBubble(msg, isOutgoing, palette)
            }
        }

        // ── Typing indicator overlay ───────────────────
        if (typingUsers.contains(toUin)) {
            Text(
                "печатает",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 11.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = TextAlign.Start,
                color = palette.textSecondary
            )
        }

        // ── Input ───────────────────────────────────────
        var showSmileyPicker by remember { mutableStateOf(false) }

        if (showSmileyPicker) {
            com.iromashka.ui.smileys.SmileyPickerPanel { shortcode ->
                inputText = inputText + shortcode
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showSmileyPicker = !showSmileyPicker }) {
                Icon(
                    Icons.Default.EmojiEmotions,
                    contentDescription = "Смайлики",
                    tint = if (showSmileyPicker) palette.accent else palette.textSecondary
                )
            }
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
private fun MessageBubble(
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
            com.iromashka.ui.smileys.SmileyText(
                text = msg.text,
                color = if (isOutgoing) androidx.compose.ui.graphics.Color.White else palette.textPrimary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            val timeStr = msg.formattedTime()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(timeStr, fontSize = 10.sp,
                    color = if (isOutgoing) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f)
                    else palette.textSecondary)
                if (isOutgoing) {
                    Spacer(Modifier.width(4.dp))
                    val status = msg.status
                    if (status != null) {
                        when (status) {
                            MessageStatus.Sent ->
                                Icon(Icons.Default.Check, null,
                                    tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(12.dp))
                            MessageStatus.Delivered ->
                                Text("✓✓", fontSize = 10.sp,
                                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f))
                            MessageStatus.Read ->
                                Text("✓✓", fontSize = 10.sp,
                                    color = androidx.compose.ui.graphics.Color(0xFF4FC3F7))
                        }
                    } else {
                        Icon(Icons.Default.Check, null,
                            tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp))
                    }
                }
            }
        }
    }
}

private fun playSound(ctx: android.content.Context, type: String) {
    val resId = when (type) {
        "outgoing" -> com.iromashka.R.raw.outgoing_message
        else -> com.iromashka.R.raw.incoming_message
    }
    if (resId != 0) {
        runCatching {
            val mp = MediaPlayer.create(ctx, resId)
            mp.setOnCompletionListener { mp.release() }
            mp.start()
        }
    }
}

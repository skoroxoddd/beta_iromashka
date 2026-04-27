package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.AttachFile
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.iromashka.media.MediaUtils
import java.io.File

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

    // Media state
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var recFile by remember { mutableStateOf<File?>(null) }

    fun startAudioRec() {
        runCatching {
            val dir = File(ctx.cacheDir, "rec").apply { mkdirs() }
            val f = File(dir, "r_${System.currentTimeMillis()}.mp4")
            val r = MediaUtils.newRecorder(ctx).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            recorder = r
            recFile = f
            recording = true
        }.onFailure {
            android.util.Log.e("ChatScreen", "rec start ${it.message}")
        }
    }

    fun stopAndSendAudio() {
        runCatching {
            recorder?.stop(); recorder?.release(); recorder = null
            recording = false
            val f = recFile ?: return
            val tag = MediaUtils.audioFileToHtmlTag(f) ?: return
            viewModel.sendMessage(toUin, tag)
            playSound(ctx, "outgoing")
        }.onFailure { android.util.Log.e("ChatScreen", "rec stop ${it.message}") }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAudioRec()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val tag = MediaUtils.imageUriToHtmlTag(ctx, uri)
            if (tag != null) {
                viewModel.sendMessage(toUin, tag)
                playSound(ctx, "outgoing")
            }
        }
    }

    // Auto-scroll only when user is at the bottom (last 2 items visible)
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= messages.size - 2
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isAtBottom) {
            listState.animateScrollToItem(messages.size - 1)
            viewModel.markChatRead(toUin)
        }
    }
    LaunchedEffect(toUin) { viewModel.markChatRead(toUin) }

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
                var showMenu by remember { mutableStateOf(false) }
                var showEdit by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    MessageBubble(msg, isOutgoing, palette,
                        onLongPress = { if (isOutgoing) showMenu = true },
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Изменить") }, onClick = { showMenu = false; showEdit = true })
                        DropdownMenuItem(text = { Text("Удалить у всех", color = androidx.compose.ui.graphics.Color.Red) },
                            onClick = { showMenu = false; viewModel.deleteMessageForAll(toUin, msg.timestamp) })
                    }
                }
                if (showEdit) {
                    EditMessageDialog(initial = msg.text.removeSuffix("  · изм."),
                        onDismiss = { showEdit = false },
                        onSave = { newText -> showEdit = false; viewModel.editMessageForAll(toUin, msg.timestamp, newText) })
                }
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
        var showAttachMenu by remember { mutableStateOf(false) }
        var showTtlMenu by remember { mutableStateOf(false) }
        val currentTtl = remember(toUin) { com.iromashka.storage.Prefs.getChatTtlSec(ctx, toUin) }
        var ttlState by remember { mutableStateOf(currentTtl) }

        if (showSmileyPicker) {
            com.iromashka.ui.smileys.SmileyPickerPanel { shortcode ->
                inputText = inputText + shortcode
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                IconButton(onClick = { showAttachMenu = true }) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Прикрепить",
                        tint = palette.textSecondary
                    )
                }
                DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Фото") },
                        leadingIcon = { Icon(Icons.Default.Image, null) },
                        onClick = {
                            showAttachMenu = false
                            pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Смайлики") },
                        leadingIcon = { Icon(Icons.Default.EmojiEmotions, null) },
                        onClick = {
                            showAttachMenu = false
                            showSmileyPicker = !showSmileyPicker
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (ttlState > 0) "Самоудаление: вкл" else "Самоудаление") },
                        leadingIcon = { Icon(Icons.Default.Schedule, null,
                            tint = if (ttlState > 0) palette.accent else androidx.compose.ui.graphics.Color.Unspecified) },
                        onClick = {
                            showAttachMenu = false
                            showTtlMenu = true
                        }
                    )
                }
                DropdownMenu(expanded = showTtlMenu, onDismissRequest = { showTtlMenu = false }) {
                    listOf(0 to "Выкл", 30 to "30 сек", 300 to "5 мин", 3600 to "1 час", 86400 to "24 часа", 604800 to "7 дней").forEach { (sec, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            com.iromashka.storage.Prefs.setChatTtlSec(ctx, toUin, sec)
                            ttlState = sec
                            showTtlMenu = false
                        })
                    }
                }
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
                maxLines = 5,
            )
            Spacer(Modifier.width(4.dp))
            if (inputText.isNotBlank()) {
                IconButton(
                    onClick = {
                        viewModel.sendMessage(toUin, inputText.trim())
                        isUserTyping = false
                        viewModel.sendTyping(toUin, false)
                        playSound(ctx, "outgoing")
                        inputText = ""
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = palette.accent)
                }
            } else {
                IconButton(onClick = {
                    val perm = android.Manifest.permission.RECORD_AUDIO
                    if (ContextCompat.checkSelfPermission(ctx, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        micPermission.launch(perm)
                    } else {
                        if (recording) stopAndSendAudio() else startAudioRec()
                    }
                }) {
                    Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (recording) "Стоп" else "Запись",
                        tint = if (recording) androidx.compose.ui.graphics.Color.Red else palette.accent)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: com.iromashka.viewmodel.ChatMessage,
    isOutgoing: Boolean,
    palette: com.iromashka.ui.theme.ThemePalette,
    onLongPress: () -> Unit = {}
) {
    val bubbleColor = if (isOutgoing) palette.bubbleOut else palette.bubbleIn
    val textColor = if (isOutgoing) palette.textPrimary else palette.textPrimary
    val timeColor = palette.textMuted
    val bubbleShape = if (isOutgoing)
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    else
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(bubbleShape)
                .background(bubbleColor)
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 11.dp, vertical = 7.dp)
        ) {
            if (MediaUtils.isMediaTag(msg.text)) {
                MediaBubble(tag = msg.text) { fallback ->
                    com.iromashka.ui.smileys.SmileyText(
                        text = fallback,
                        color = textColor,
                        fontSize = 14.5.sp
                    )
                }
            } else {
                com.iromashka.ui.smileys.SmileyText(
                    text = msg.text,
                    color = textColor,
                    fontSize = 14.5.sp
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 1.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(msg.formattedTime(), fontSize = 11.sp, color = timeColor)
                if (isOutgoing) {
                    Spacer(Modifier.width(3.dp))
                    when (msg.status) {
                        MessageStatus.Read ->
                            Text("✓✓", fontSize = 11.sp, color = palette.accent)
                        MessageStatus.Delivered ->
                            Text("✓✓", fontSize = 11.sp, color = timeColor)
                        else ->
                            Text("✓", fontSize = 11.sp, color = timeColor)
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


@Composable
private fun EditMessageDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Изменить сообщение") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 5)
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

package com.iromashka.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.iromashka.media.MediaUtils
import java.io.File
import kotlinx.coroutines.launch

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

    // Media state - stored in ViewModel to survive config changes
    var recording by remember { mutableStateOf(false) }
    val recorderRef = remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    val recFileRef = remember { mutableStateOf<File?>(null) }

    // Cleanup recorder on exit
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                recorderRef.value?.stop()
                recorderRef.value?.release()
            }
            recorderRef.value = null
        }
    }

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
            recorderRef.value = r
            recFileRef.value = f
            recording = true
        }.onFailure {
            android.util.Log.e("ChatScreen", "rec start ${it.message}")
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun stopAndSendAudio() {
        runCatching {
            recorderRef.value?.stop(); recorderRef.value?.release(); recorderRef.value = null
            recording = false
            val f = recFileRef.value ?: return
            scope.launch {
                val tag = MediaUtils.audioFileToHtmlTag(ctx, f) ?: return@launch
                viewModel.sendMessage(toUin, tag)
                playSound(ctx, "outgoing")
            }
        }.onFailure { android.util.Log.e("ChatScreen", "rec stop ${it.message}") }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAudioRec()
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val tag = MediaUtils.imageUriToHtmlTag(ctx, uri)
                if (tag != null) {
                    viewModel.sendMessage(toUin, tag)
                    playSound(ctx, "outgoing")
                }
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

    // Initial scroll to bottom when chat opens — wait for messages to load
    var initialScrollDone by remember { mutableStateOf(false) }
    LaunchedEffect(toUin, messages.size) {
        if (messages.isNotEmpty() && !initialScrollDone) {
            listState.scrollToItem(messages.size - 1)
            initialScrollDone = true
            viewModel.markChatRead(toUin)
        }
    }

    // Auto-scroll on new messages only if already at bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && initialScrollDone && isAtBottom) {
            listState.animateScrollToItem(messages.size - 1)
            viewModel.markChatRead(toUin)
        }
    }

    // Sound on incoming message
    var lastCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > lastCount) {
            val newMsgs = messages.drop(lastCount)
            val hasIncoming = newMsgs.any { !it.isOutgoing }
            if (hasIncoming) playSound(ctx, "incoming")
        }
        lastCount = messages.size
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
                .padding(horizontal = 8.dp, vertical = 6.dp)
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

        // PWA-style input area
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.surface,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
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
                // PWA-style rounded input field with border
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
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .border(
                            width = 1.5.dp,
                            color = palette.divider,
                            shape = RoundedCornerShape(22.dp)
                        ),
                    placeholder = { Text("Сообщение...", color = palette.textSecondary) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.inputBg,
                        unfocusedContainerColor = palette.inputBg,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                    ),
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 5,
                )
                Spacer(Modifier.width(6.dp))
                // Circular send/mic button
                val buttonColor = palette.accent
                androidx.compose.material3.FloatingActionButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            viewModel.sendMessage(toUin, inputText.trim())
                            isUserTyping = false
                            viewModel.sendTyping(toUin, false)
                            playSound(ctx, "outgoing")
                            inputText = ""
                        } else {
                            val perm = android.Manifest.permission.RECORD_AUDIO
                            if (ContextCompat.checkSelfPermission(ctx, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                micPermission.launch(perm)
                            } else {
                                if (recording) stopAndSendAudio() else startAudioRec()
                            }
                        }
                    },
                    modifier = Modifier.size(48.dp),
                    containerColor = if (recording) androidx.compose.ui.graphics.Color.Red else buttonColor,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(2.dp, 4.dp),
                ) {
                    Icon(
                        if (inputText.isNotBlank()) Icons.AutoMirrored.Filled.Send
                        else if (recording) Icons.Default.Stop
                        else Icons.Default.Mic,
                        contentDescription = if (inputText.isNotBlank()) "Отправить" else if (recording) "Стоп" else "Запись"
                    )
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
    val textColor = palette.textPrimary
    val timeColor = palette.textMuted

    // PWA-style bubble shapes with tail effect
    val bubbleShape = if (isOutgoing)
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    else
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = bubbleShape,
            color = bubbleColor,
            shadowElevation = 1.dp,
            tonalElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onLongPress)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (MediaUtils.isMediaTag(msg.text)) {
                    MediaBubble(tag = msg.text) { fallback ->
                        com.iromashka.ui.smileys.SmileyText(
                            text = fallback,
                            color = textColor,
                            fontSize = 15.sp
                        )
                    }
                } else {
                    com.iromashka.ui.smileys.SmileyText(
                        text = msg.text,
                        color = textColor,
                        fontSize = 15.sp
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(msg.formattedTime(), fontSize = 11.sp, color = timeColor)
                    if (isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        when (msg.status) {
                            MessageStatus.Read ->
                                Text("✓✓", fontSize = 12.sp, color = palette.accent)
                            MessageStatus.Delivered ->
                                Text("✓✓", fontSize = 12.sp, color = timeColor)
                            else ->
                                Text("✓", fontSize = 12.sp, color = timeColor)
                        }
                    }
                }
            }
        }
    }
}

private var activePlayers = mutableListOf<MediaPlayer>()

private fun playSound(ctx: android.content.Context, type: String) {
    val resId = when (type) {
        "outgoing" -> com.iromashka.R.raw.outgoing_message
        else -> com.iromashka.R.raw.incoming_message
    }
    if (resId != 0) {
        runCatching {
            val mp = MediaPlayer.create(ctx, resId)
            activePlayers.add(mp)
            mp.setOnCompletionListener {
                mp.release()
                activePlayers.remove(mp)
            }
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

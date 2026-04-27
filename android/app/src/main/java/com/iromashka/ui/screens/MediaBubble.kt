package com.iromashka.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.iromashka.media.MediaUtils
import java.io.File
import java.io.FileOutputStream

@Composable
fun MediaBubble(tag: String, onTextFallback: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    val src = MediaUtils.extractSrc(tag)
    if (src == null) { onTextFallback(tag); return }
    val mime = MediaUtils.extractMime(tag) ?: "application/octet-stream"

    when {
        mime.startsWith("image/") -> {
            val request = ImageRequest.Builder(ctx)
                .data(src)
                .crossfade(true)
                .build()
            AsyncImage(
                model = request,
                contentDescription = "image",
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }
        mime.startsWith("audio/") || mime.startsWith("video/") -> {
            // MediaPlayer can't read data: URIs reliably — write to tmp file once, play from there.
            val cachedUri = remember(src) { dataUriToCachedFile(ctx, src, mime) }
            if (cachedUri == null) onTextFallback("[медиа: не удалось декодировать]")
            else AudioPlayer(cachedUri = cachedUri)
        }
        else -> onTextFallback(tag)
    }
}

private fun dataUriToCachedFile(ctx: android.content.Context, dataUri: String, mime: String): Uri? {
    val bytes = MediaUtils.decodeDataUri(dataUri) ?: return null
    val ext = when {
        mime.contains("mp4") -> "mp4"
        mime.contains("aac") -> "aac"
        mime.contains("webm") -> "webm"
        mime.contains("mpeg") -> "mp3"
        else -> "bin"
    }
    val dir = File(ctx.cacheDir, "media").apply { mkdirs() }
    val name = "m_${bytes.contentHashCode()}.$ext"
    val f = File(dir, name)
    if (!f.exists() || f.length() != bytes.size.toLong()) {
        FileOutputStream(f).use { it.write(bytes) }
    }
    // Internal file URI is enough for in-process MediaPlayer; no FileProvider needed.
    return Uri.fromFile(f)
}

@Composable
private fun AudioPlayer(cachedUri: Uri) {
    val ctx = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(cachedUri) {
        onDispose { player?.release(); player = null }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color(0x22000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        IconButton(onClick = {
            runCatching {
                if (playing) {
                    player?.pause(); playing = false
                } else {
                    if (player == null) {
                        player = MediaPlayer().apply {
                            setDataSource(ctx, cachedUri)
                            setOnCompletionListener { playing = false }
                            setOnErrorListener { _, what, extra ->
                                error = "play err $what/$extra"
                                playing = false
                                true
                            }
                            prepare()
                        }
                    }
                    player?.start()
                    playing = true
                }
            }.onFailure { error = it.message }
        }) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "pause" else "play")
        }
        Text(
            text = error ?: if (playing) "▶ играет..." else "🎵 голосовое",
            color = if (error != null) Color.Red else Color.Unspecified,
            fontSize = androidx.compose.ui.unit.TextUnit.Unspecified
        )
    }
}

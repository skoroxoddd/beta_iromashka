package com.iromashka.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File

object MediaUtils {

    private const val TAG = "MediaUtils"

    fun imageUriToHtmlTag(ctx: Context, uri: Uri): String? = runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > 1_500_000) {
            Log.w(TAG, "Image too large: ${bytes.size}b — refusing")
            return null
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "<img src=\"data:$mime;base64,$b64\" />"
    }.getOrNull()

    fun audioFileToHtmlTag(file: File, mime: String = "audio/mp4"): String? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size > 1_500_000) {
            Log.w(TAG, "Audio too large: ${bytes.size}b — refusing")
            return null
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        "<audio src=\"data:$mime;base64,$b64\" controls />"
    }.getOrNull()

    fun isMediaTag(text: String): Boolean {
        val t = text.trimStart()
        return t.startsWith("<img ") || t.startsWith("<audio ") || t.startsWith("<video ")
    }

    fun extractSrc(tag: String): String? {
        val m = Regex("""src\s*=\s*"([^"]+)"""").find(tag) ?: return null
        return m.groupValues[1]
    }

    fun extractMime(tag: String): String? {
        val src = extractSrc(tag) ?: return null
        val m = Regex("""^data:([^;,]+)""").find(src) ?: return null
        return m.groupValues[1]
    }

    fun decodeDataUri(dataUri: String): ByteArray? = runCatching {
        val payloadIdx = dataUri.indexOf(",")
        if (payloadIdx < 0) return null
        val payload = dataUri.substring(payloadIdx + 1)
        Base64.decode(payload, Base64.DEFAULT)
    }.getOrNull()

    fun newRecorder(ctx: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
    }
}

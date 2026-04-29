package com.iromashka.media

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.iromashka.network.ApiService
import com.iromashka.storage.Prefs
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

object MediaUtils {

    private const val TAG = "MediaUtils"
    private const val MAX_BYTES = 25 * 1024 * 1024

    /**
     * F2: upload blob via /api/upload, return server URL embedded in <img>/<audio>/<video> tag.
     * Falls back to null on error — caller handles UI feedback.
     */
    suspend fun imageUriToHtmlTag(ctx: Context, uri: Uri): String? = runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_BYTES) {
            Log.w(TAG, "Image too large: ${bytes.size}b — refusing")
            return null
        }
        val url = uploadBytes(ctx, bytes, mime, "image.${extOfMime(mime, "jpg")}") ?: return null
        "<img src=\"$url\" />"
    }.getOrNull()

    suspend fun audioFileToHtmlTag(ctx: Context, file: File, mime: String = "audio/mp4"): String? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size > MAX_BYTES) {
            Log.w(TAG, "Audio too large: ${bytes.size}b — refusing")
            return null
        }
        val url = uploadBytes(ctx, bytes, mime, "audio.${extOfMime(mime, "mp4")}") ?: return null
        "<audio src=\"$url\" controls />"
    }.getOrNull()

    private suspend fun uploadBytes(ctx: Context, bytes: ByteArray, mime: String, fileName: String): String? {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) {
            Log.w(TAG, "uploadBytes: no auth token")
            return null
        }
        return runCatching {
            val rb = bytes.toRequestBody(mime.toMediaTypeOrNull(), 0, bytes.size)
            val part = MultipartBody.Part.createFormData("file", fileName, rb)
            val expected = "1".toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = ApiService.api.uploadBlob("Bearer $token", part, expected)
            resp.url
        }.onFailure {
            Log.e(TAG, "uploadBytes failed: ${it.message}")
        }.getOrNull()
    }

    private fun extOfMime(mime: String, fallback: String): String = when {
        mime.contains("jpeg") -> "jpg"
        mime.contains("png")  -> "png"
        mime.contains("webp") -> "webp"
        mime.contains("gif")  -> "gif"
        mime.contains("mp4")  -> "mp4"
        mime.contains("aac")  -> "aac"
        mime.contains("mpeg") -> "mp3"
        mime.contains("ogg")  -> "ogg"
        else -> fallback
    }

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
        // data-URI legacy
        Regex("""^data:([^;,]+)""").find(src)?.let { return it.groupValues[1] }
        // server URL — guess from extension
        val ext = src.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"  -> "image/png"
            "webp" -> "image/webp"
            "gif"  -> "image/gif"
            "mp4"  -> "video/mp4"
            "aac"  -> "audio/aac"
            "mp3"  -> "audio/mpeg"
            "ogg"  -> "audio/ogg"
            else -> null
        }
    }

    fun decodeDataUri(dataUri: String): ByteArray? = runCatching {
        val payloadIdx = dataUri.indexOf(",")
        if (payloadIdx < 0) return null
        val payload = dataUri.substring(payloadIdx + 1)
        android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
    }.getOrNull()

    fun newRecorder(ctx: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx)
        else @Suppress("DEPRECATION") MediaRecorder()
    }
}

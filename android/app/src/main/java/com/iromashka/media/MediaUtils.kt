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
        if (t.startsWith("<img ") || t.startsWith("<audio ") || t.startsWith("<video ")) return true
        return false
    }

    /** PWA E2E media tag: <img iromedia='{"u":"/uploads/...","kh":"...","ih":"...","m":"image/jpeg",...}'> */
    data class IromediaMeta(
        val url: String,
        val keyBytes: ByteArray,
        val ivBytes: ByteArray,
        val mime: String
    )

    fun extractIromediaMeta(tag: String): IromediaMeta? = runCatching {
        val attr = Regex("""iromedia\s*=\s*'([^']+)'""").find(tag)?.groupValues?.getOrNull(1)
            ?: Regex("""iromedia\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.getOrNull(1)
            ?: return null
        // PWA escapes single quotes as &#39; — undo before JSON parse.
        val json = attr.replace("&#39;", "'").replace("&quot;", "\"")
        val obj = org.json.JSONObject(json)
        val url = obj.optString("u").ifEmpty { obj.optString("url") }
        if (url.isEmpty()) return null
        val keyHex = obj.optString("kh").ifEmpty { null }
        val ivHex = obj.optString("ih").ifEmpty { null }
        val keyB64 = obj.optString("k").ifEmpty { obj.optString("keyB64").ifEmpty { null } }
        val ivB64 = obj.optString("i").ifEmpty { obj.optString("ivB64").ifEmpty { null } }
        val keyBytes = when {
            keyHex != null -> hexToBytes(keyHex)
            keyB64 != null -> android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP)
            else -> return null
        } ?: return null
        val ivBytes = when {
            ivHex != null -> hexToBytes(ivHex)
            ivB64 != null -> android.util.Base64.decode(ivB64, android.util.Base64.NO_WRAP)
            else -> return null
        } ?: return null
        val mime = obj.optString("m").ifEmpty { obj.optString("mime").ifEmpty { "image/jpeg" } }
        IromediaMeta(url, keyBytes, ivBytes, mime)
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray? = runCatching {
        if (hex.length % 2 != 0) return null
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()

    /** Download `/uploads/...` blob, AES-GCM decrypt with iromedia key/iv, return plaintext bytes. */
    suspend fun fetchAndDecryptIromedia(ctx: Context, meta: IromediaMeta): ByteArray? = runCatching {
        val token = Prefs.getToken(ctx)
        val absUrl = if (meta.url.startsWith("http")) meta.url else "https://iromashka.ru${meta.url}"
        val req = okhttp3.Request.Builder()
            .url(absUrl)
            .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
            .build()
        val resp = ApiService.okHttpClient.newCall(req).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "iromedia fetch ${r.code}")
                return@runCatching null
            }
            val ct = r.body?.bytes() ?: return@runCatching null
            val keySpec = javax.crypto.spec.SecretKeySpec(meta.keyBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, meta.ivBytes))
            cipher.doFinal(ct)
        }
    }.onFailure { Log.w(TAG, "iromedia decrypt failed: ${it.message}") }.getOrNull()

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

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
     * F2: AES-GCM encrypt → upload ciphertext via /api/upload → return iromedia tag (E2E).
     * Matches PWA buildIromediaTag: <img iromedia='{"u":...,"kh":...,"ih":...,"m":...,"s":...,"n":...}'>
     */
    suspend fun imageUriToHtmlTag(ctx: Context, uri: Uri): String? = runCatching {
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > MAX_BYTES) {
            Log.w(TAG, "Image too large: ${bytes.size}b — refusing")
            return null
        }
        encryptAndUpload(ctx, bytes, mime, "img", "image.${extOfMime(mime, "jpg")}")
    }.getOrNull()

    suspend fun audioFileToHtmlTag(ctx: Context, file: File, mime: String = "audio/mp4"): String? = runCatching {
        val bytes = file.readBytes()
        if (bytes.size > MAX_BYTES) {
            Log.w(TAG, "Audio too large: ${bytes.size}b — refusing")
            return null
        }
        encryptAndUpload(ctx, bytes, mime, "audio", "audio.${extOfMime(mime, "mp4")}")
    }.getOrNull()

    private suspend fun encryptAndUpload(ctx: Context, plain: ByteArray, mime: String, tagName: String, fileName: String): String? {
        val token = Prefs.getToken(ctx)
        if (token.isEmpty()) { Log.w(TAG, "encryptAndUpload: no token"); return null }
        val keyRaw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(keyRaw, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        Log.i(TAG, "encryptAndUpload plain=${plain.size} ct=${ct.size} mime=$mime")
        return runCatching {
            val rb = ct.toRequestBody("application/octet-stream".toMediaTypeOrNull(), 0, ct.size)
            val part = MultipartBody.Part.createFormData("file", "m.bin", rb)
            val expected = "1".toRequestBody("text/plain".toMediaTypeOrNull())
            val resp = ApiService.api.uploadBlob("Bearer $token", part, expected)
            val keyHex = bytesToHex(keyRaw)
            val ivHex = bytesToHex(iv)
            val meta = org.json.JSONObject()
                .put("u", resp.url)
                .put("kh", keyHex)
                .put("ih", ivHex)
                .put("m", mime)
                .put("s", plain.size)
                .put("n", fileName)
            val attr = meta.toString().replace("'", "&#39;")
            "<$tagName iromedia='$attr'></$tagName>"
        }.onFailure { Log.e(TAG, "upload failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            if (v < 0x10) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
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

    // In-memory cache of decrypted bytes, keyed by url+keyHex.
    // Avoids re-downloading and re-decrypting on every recomposition.
    private val memCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ByteArray?>>()

    private fun cacheKey(meta: IromediaMeta): String = meta.url + "#" + bytesToHex(meta.keyBytes)

    /** Download `/uploads/...` blob, AES-GCM decrypt with iromedia key/iv, return plaintext bytes. */
    suspend fun fetchAndDecryptIromedia(ctx: Context, meta: IromediaMeta): ByteArray? {
        val key = cacheKey(meta)
        memCache[key]?.let { return it }
        // Coalesce concurrent requests for the same key.
        val existing = inFlight[key]
        if (existing != null) return existing.await()
        return kotlinx.coroutines.coroutineScope {
            val deferred = kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) {
                doFetchAndDecrypt(ctx, meta)?.also { memCache[key] = it }
            }
            inFlight[key] = deferred
            try { deferred.await() } finally { inFlight.remove(key) }
        }
    }

    private fun doFetchAndDecrypt(ctx: Context, meta: IromediaMeta): ByteArray? {
        val absUrl = if (meta.url.startsWith("http")) meta.url else "https://iromashka.ru${meta.url}"
        Log.i(TAG, "iromedia start url=$absUrl key.len=${meta.keyBytes.size} iv.len=${meta.ivBytes.size} mime=${meta.mime}")
        val req = okhttp3.Request.Builder().url(absUrl).build()
        val ct: ByteArray = try {
            ApiService.okHttpClient.newCall(req).execute().use { r ->
                Log.i(TAG, "iromedia http=${r.code} ct-type=${r.header("Content-Type")} ct-len=${r.header("Content-Length")}")
                if (!r.isSuccessful) {
                    Log.w(TAG, "iromedia fetch failed code=${r.code}")
                    return null
                }
                r.body?.bytes() ?: run {
                    Log.w(TAG, "iromedia empty body")
                    return null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "iromedia http exception ${e.javaClass.simpleName} ${e.message}")
            return null
        }
        Log.i(TAG, "iromedia got ct.size=${ct.size} head=${ct.take(8).joinToString(",") { (it.toInt() and 0xff).toString(16) }}")
        return try {
            val keySpec = javax.crypto.spec.SecretKeySpec(meta.keyBytes, "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, javax.crypto.spec.GCMParameterSpec(128, meta.ivBytes))
            val pt = cipher.doFinal(ct)
            Log.i(TAG, "iromedia decrypt OK pt.size=${pt.size}")
            pt
        } catch (e: Throwable) {
            Log.e(TAG, "iromedia decrypt EX ${e.javaClass.simpleName} msg=${e.message} ct.size=${ct.size}")
            null
        }
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

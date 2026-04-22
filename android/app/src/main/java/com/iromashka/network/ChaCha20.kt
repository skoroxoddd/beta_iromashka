package com.iromashka.network

/**
 * Pure-Kotlin ChaCha20 (RFC 7539).
 * Used as fallback on API < 28 where javax.crypto doesn't support "ChaCha20".
 * On API 28+ the system cipher is preferred.
 */
object ChaCha20 {

    fun crypt(key: ByteArray, nonce: ByteArray, data: ByteArray, counter: Int = 0): ByteArray {
        require(key.size == 32) { "key must be 32 bytes" }
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val out = data.copyOf()
        var pos = 0
        var blockCounter = counter
        while (pos < out.size) {
            val block = block(key, nonce, blockCounter)
            val len = minOf(64, out.size - pos)
            for (i in 0 until len) out[pos + i] = (out[pos + i].toInt() xor block[i].toInt()).toByte()
            pos += len
            blockCounter++
        }
        return out
    }

    private fun block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
        val s = IntArray(16)
        // constants
        s[0] = 0x61707865.toInt(); s[1] = 0x3320646e.toInt()
        s[2] = 0x79622d32.toInt(); s[3] = 0x6b206574.toInt()
        // key (8 words)
        for (i in 0..7) s[4 + i] = key.leInt(i * 4)
        // counter
        s[12] = counter
        // nonce (3 words)
        s[13] = nonce.leInt(0); s[14] = nonce.leInt(4); s[15] = nonce.leInt(8)

        val w = s.copyOf()
        repeat(10) {
            qr(w, 0, 4, 8, 12); qr(w, 1, 5, 9, 13); qr(w, 2, 6, 10, 14); qr(w, 3, 7, 11, 15)
            qr(w, 0, 5, 10, 15); qr(w, 1, 6, 11, 12); qr(w, 2, 7, 8, 13); qr(w, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0..15) {
            val v = w[i] + s[i]
            out[i * 4 + 0] = (v and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private fun qr(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotl(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotl(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotl(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotl(7)
    }

    private fun Int.rotl(n: Int): Int = (this shl n) or (this ushr (32 - n))
    private fun ByteArray.leInt(off: Int): Int =
        (this[off].toInt() and 0xFF) or
        ((this[off+1].toInt() and 0xFF) shl 8) or
        ((this[off+2].toInt() and 0xFF) shl 16) or
        ((this[off+3].toInt() and 0xFF) shl 24)
}

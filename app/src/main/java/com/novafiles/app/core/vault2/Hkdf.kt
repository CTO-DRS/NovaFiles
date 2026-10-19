package com.novafiles.app.core.vault2

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — HKDF-SHA256 (RFC 5869): اشتقاق مفاتيح لكل مجلد/ملف/سجل تحت مفتاح جذر واحد.
 *
 *  - Extract: HMAC-SHA256(salt, ikm) → PRK
 *  - Expand:  سلسلة HMAC-SHA256(PRK, info || counter) → OKM بطول مطلوب (≤ 255×32 بايت)
 *
 * خالص JVM — مُختبر بمتجهات RFC 5869 الرسمية.
 */
object Hkdf {

    private const val HASH_LEN = 32

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val realSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(realSalt, ikm)
    }

    fun expand(prk: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        require(outLen in 1..255 * HASH_LEN) { "invalid HKDF output length: $outLen" }
        val n = (outLen + HASH_LEN - 1) / HASH_LEN
        val okm = ByteArray(outLen)
        var t = ByteArray(0)
        var pos = 0
        for (i in 1..n) {
            val input = t + info + byteArrayOf(i.toByte())
            t = hmacSha256(prk, input)
            val copy = minOf(HASH_LEN, outLen - pos)
            System.arraycopy(t, 0, okm, pos, copy)
            pos += copy
        }
        return okm
    }

    /** derive(ikm, salt, info, len) — الاختصار القياسي extract ثم expand */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray =
        expand(extract(salt, ikm), info, outLen)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

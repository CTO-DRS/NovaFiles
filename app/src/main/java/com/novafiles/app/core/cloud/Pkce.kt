package com.novafiles.app.core.cloud

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v0.16 — PKCE (Proof Key for Code Exchange, RFC 7636) ل تدفّق OAuth 2.0.
 *
 * الثلاثي (Drive/Dropbox/OneDrive) كلهم يقبلون (وأندرويد يتطلب) PKCE مع
 * العملاء العموميين بلا سر عميل. المولّد: code_verifier عشوائي 128 حرفاً
 * من أبجدية unreserved (RFC 3986 §2.3)، وchallenge = BASE64URL(SHA256(verifier)).
 */
object Pkce {

    /** أبجدية verifier المسموحة: ALPHA / DIGIT / "-" / "." / "_" / "~" */
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** طول verifier — RFC يسمح 43..128، نستخدم 128 (أقصى إنتروبيا) */
    const val VERIFIER_LENGTH = 128

    /** زوج verifier/challenge جاهز للتفويض */
    data class Pair(val verifier: String, val challenge: String)

    /** يولد زوجاً جديداً — SecureRandom لكل استدعاء (لا إعادة استخدام) */
    fun generate(random: SecureRandom = SecureRandom()): Pair {
        val sb = StringBuilder(VERIFIER_LENGTH)
        repeat(VERIFIER_LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return Pair(verifier = sb.toString(), challenge = s256(sb.toString()))
    }

    /** طريقة S256 الإلزامية: BASE64URL-بدون-حشو(SHA256(ascii(verifier))) */
    fun s256(verifier: String): String {
        require(verifier.length in 43..128) { "verifier length out of RFC range" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    /** BASE64URL بلا حشو '=' (متطلب RFC 7636 §4.2) */
    fun base64UrlNoPadding(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

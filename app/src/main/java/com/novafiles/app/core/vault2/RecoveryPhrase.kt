package com.novafiles.app.core.vault2

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v0.15 — عبارة الاسترجاع: 12 كلمة BIP39 قياسية (128 بت عشوائية + 4 بت تحقق).
 *
 * «فقدان المفتاح لم يعد فقدان بيانات» — العبارة الوحيدة البشرية القابلة للكتابة
 * على ورقة التي تستعيد الخزنة كاملة على جهاز جديد. التحقق من checksum يمنع
 * أخطاء النسخ الصامتة.
 */
object RecoveryPhrase {

    const val WORD_COUNT = 12
    private const val ENTROPY_BITS = 128
    private const val CHECKSUM_BITS = 4 // ENTROPY_BITS/32

    /** يولّد عبارة جديدة عشوائية */
    fun generate(random: SecureRandom = SecureRandom()): List<String> {
        val entropy = ByteArray(ENTROPY_BITS / 8).also { random.nextBytes(it) }
        return fromEntropy(entropy)
    }

    /** من إنتروبيا خام (للاختبارات بمتجهات ثابتة) */
    fun fromEntropy(entropy: ByteArray): List<String> {
        require(entropy.size == ENTROPY_BITS / 8) { "need 16 entropy bytes" }
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        // 132 بت = 16 بايت إنتروبيا + أول 4 بتات تحقق
        val bits = BooleanArray(132)
        for (i in entropy.indices) {
            for (b in 0 until 8) bits[i * 8 + b] = (entropy[i].toInt() shr (7 - b)) and 1 == 1
        }
        for (b in 0 until CHECKSUM_BITS) {
            bits[128 + b] = (hash[0].toInt() shr (7 - b)) and 1 == 1
        }
        val words = mutableListOf<String>()
        for (i in 0 until 12) {
            var idx = 0
            for (b in 0 until 11) idx = (idx shl 1) or (if (bits[i * 11 + b]) 1 else 0)
            words.add(Bip39Wordlist.WORDS[idx])
        }
        return words
    }

    /** يتحقق من صحة عبارة (12 كلمة، ضمن القائمة، checksum سليم) */
    fun validate(words: List<String>): Boolean {
        if (words.size != WORD_COUNT) return false
        val indices = IntArray(WORD_COUNT)
        for (i in 0 until WORD_COUNT) {
            val idx = Bip39Wordlist.WORDS.indexOf(words[i].trim().lowercase())
            if (idx < 0) return false
            indices[i] = idx
        }
        val bits = BooleanArray(132)
        for (i in 0 until 12) {
            for (b in 0 until 11) bits[i * 11 + b] = (indices[i] shr (10 - b)) and 1 == 1
        }
        val entropy = ByteArray(16)
        for (i in 0 until 16) {
            var byte = 0
            for (b in 0 until 8) byte = (byte shl 1) or (if (bits[i * 8 + b]) 1 else 0)
            entropy[i] = byte.toByte()
        }
        val expected = fromEntropy(entropy)
        return expected == words.map { it.trim().lowercase() }
    }

    /** يشتق مفتاح التغليف 32B من العبارة (PBKDF2-HMAC-SHA256) */
    fun deriveKey(words: List<String>, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        require(validate(words)) { "invalid recovery phrase" }
        val spec = javax.crypto.spec.PBEKeySpec(
            words.joinToString(" ") { it.trim().lowercase() }.toCharArray(),
            salt,
            iterations,
            256,
        )
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    const val PBKDF2_ITERATIONS = 120_000
}

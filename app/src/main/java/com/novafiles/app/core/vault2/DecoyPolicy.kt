package com.novafiles.app.core.vault2

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * v0.15 — سياسة التنكّر: حاسبة حقيقية كواجهة للخزنة.
 *
 *  - الرمز الحقيقي يفتح الخزنة، رمز التنكّر يفتح «خزنة وهمية» فارغة،
 *    وأي رمز آخر يُقيَّد في سجل التسلل — سلوك الحاسبة نفسه متطابق تماماً.
 *  - التمييز يحدث فقط بعد الضغط على «=» طويلاً أو إدخال الرمز كاملاً.
 *
 * محرك الحاسبة خالص ويحاكي سلوك حاسبة أندرويد القياسية:
 * أسبقية ×÷ على +−، نسبة مئوية، علامة عشرية، ± ، جذر، عمليات متسلسلة.
 */
data class DecoyConfig(
    val realPinHash: String,
    val decoyPinHash: String,
    val salt: String,
)

sealed class DecoyOutcome {
    /** افتح الخزنة الحقيقية */
    object Real : DecoyOutcome()

    /** افتح الخزنة الوهمية (حاسبة عادية من جهة المستخدم) */
    object Decoy : DecoyOutcome()

    /** فشل — يُقيَّد */
    data class Fail(val attemptsSoFar: Int) : DecoyOutcome()
}

class DecoyPolicy(
    private var config: DecoyConfig,
    private val hasher: PinHasher = PinHasher(),
) {

    var failedAttempts: Int = 0
        private set

    fun evaluate(pin: String): DecoyOutcome = when {
        hasher.verify(pin, config.salt, config.realPinHash) -> {
            failedAttempts = 0
            DecoyOutcome.Real
        }
        hasher.verify(pin, config.salt, config.decoyPinHash) -> {
            failedAttempts = 0
            DecoyOutcome.Decoy
        }
        else -> {
            failedAttempts++
            DecoyOutcome.Fail(failedAttempts)
        }
    }

    fun updateConfig(newConfig: DecoyConfig) { config = newConfig }
}

/**
 * مُجزِّئ PIN: PBKDF2-HMAC-SHA256 بملح عشوائي لكل خزنة — ترقية عن
 * SHA-256 المملح في v0.3 (مقاومة أقوى للتخمين دون GPU).
 */
class PinHasher(
    private val iterations: Int = PBKDF2_ITERATIONS,
    private val random: SecureRandom = SecureRandom(),
) {
    fun newSalt(): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun hash(pin: String, salt: String): String {
        require(pin.length >= MIN_PIN_LEN) { "pin too short" }
        val saltBytes = salt.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), saltBytes, iterations, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded.joinToString("") { "%02x".format(it) }
    }

    fun verify(pin: String, salt: String, expected: String): Boolean =
        runCatching { hash(pin, salt) }.getOrNull() == expected

    companion object {
        const val MIN_PIN_LEN = 4
        const val PBKDF2_ITERATIONS = 60_000
    }
}

/**
 * محرك حاسبة (الوجه التنكّري): تقييم تعبير بأسبقية قياسية — يبني سلسلة
 * الرقم الحالي ويعرضها كما تفعل الحاسبة، و«=» تُقيّم التعبير المتراكم.
 * عمداً بلا اعتماديات ولا eval — سلوك حتمي مختبر.
 */
class CalculatorEngine {

    private var accumulator: Double = 0.0
    private var pendingOp: Char? = null
    private var current: StringBuilder = StringBuilder("0")
    private var freshEntry: Boolean = false // بعد «=» الرقم الجديد يبدأ صفحة نظيفة

    fun display(): String = format(current.toString())

    fun digit(d: Char) {
        require(d in '0'..'9') { "not a digit: $d" }
        if (freshEntry) {
            current = StringBuilder()
            freshEntry = false
        }
        if (current.toString() == "0" && d == '0') return
        if (current.toString() == "0") current.clear()
        if (current.length < MAX_DIGITS) current.append(d)
    }

    fun decimal() {
        if (freshEntry) {
            current = StringBuilder("0")
            freshEntry = false
        }
        if (!current.contains('.')) current.append('.')
    }

    fun setOperation(op: Char) {
        require(op in OPS) { "unknown op: $op" }
        if (pendingOp != null && !freshEntry) {
            applyPending()
        } else {
            accumulator = current.toString().toDouble()
        }
        pendingOp = op
        freshEntry = true
    }

    fun equals(): String {
        if (pendingOp != null && !freshEntry) applyPending()
        pendingOp = null
        freshEntry = true
        return display()
    }

    fun toggleSign() {
        val v = current.toString().toDoubleOrNull() ?: return
        if (v == 0.0) return
        current = StringBuilder(formatNumber(-v))
    }

    fun percent(): String {
        val v = current.toString().toDoubleOrNull() ?: return display()
        current = StringBuilder(formatNumber(v / 100.0))
        return display()
    }

    fun squareRoot(): String {
        val v = current.toString().toDoubleOrNull() ?: return display()
        if (v < 0) { current = StringBuilder("خطأ"); return display() }
        current = StringBuilder(formatNumber(kotlin.math.sqrt(v)))
        return display()
    }

    fun backspace() {
        if (freshEntry) return
        if (current.isNotEmpty()) current.deleteCharAt(current.length - 1)
        if (current.isEmpty()) current.append('0')
    }

    fun clear() {
        accumulator = 0.0
        pendingOp = null
        current = StringBuilder("0")
        freshEntry = false
    }

    private fun applyPending() {
        val b = current.toString().toDoubleOrNull() ?: return
        accumulator = when (pendingOp) {
            '+' -> accumulator + b
            '-' -> accumulator - b
            '*' -> accumulator * b
            '/' -> if (b == 0.0) Double.NaN else accumulator / b
            else -> b
        }
        current = StringBuilder(formatNumber(accumulator))
    }

    companion object {
        val OPS = setOf('+', '-', '*', '/')
        const val MAX_DIGITS = 15

        fun formatNumber(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "خطأ"
            if (v == v.toLong().toDouble() && kotlin.math.abs(v) < 1e15) {
                return v.toLong().toString()
            }
            // حدّ الصور المعنوية 10 ثم اقطع الأصفار الزائدة والنقطة اليتيمة
            val raw = String.format(java.util.Locale.US, "%.10g", v)
            return if (raw.contains('e') || raw.contains('E')) raw
            else raw.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        fun format(raw: String): String = raw
    }
}

package com.novafiles.app.core.checksum

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * v0.29.0 — ملفات البصمات بنسق GNU coreutils (pure JVM)
 *
 * التوليد: `<hex>␣␣<مسار-نسبي>` بمسافتين (نسق النص القياسي الذي تقرؤه كل الأدوات).
 * التحليل متسامح: سطر ثنائي `*`، تعليقات `#`، CRLF، حالة مختلطة للسداسي، أسطر فارغة —
 * والأسطر المعطوبة تُبلَّغ برقمها ولا تُسقط التحليل كله.
 */

/** سطر محلول من ملف بصمات */
data class ManifestEntry(
    val hash: String,
    val path: String,
    val binaryMarker: Boolean,
)

/** سطر معطوب برقمه — يُعرض للمستخدم ولا يوقف التحليل */
data class ManifestBadLine(val line: Int, val text: String)

/** ناتج التحليل */
data class ManifestParse(
    val entries: List<ManifestEntry>,
    val badLines: List<ManifestBadLine>,
)

/** حكم التحقق لسطر واحد */
sealed class VerifyOutcome {
    /** تطابق تام */
    data class Ok(val entry: ManifestEntry) : VerifyOutcome()

    /** الملف موجود لكن البصمة مختلفة — مع البصمة الفعلية */
    data class Mismatch(val entry: ManifestEntry, val actual: String) : VerifyOutcome()

    /** الملف غير موجود أو غير قابل للقراءة */
    data class Missing(val entry: ManifestEntry) : VerifyOutcome()
}

/** ملخص التحقق */
data class VerifySummary(
    val ok: Int,
    val mismatch: Int,
    val missing: Int,
    val badLines: Int,
) {
    val total: Int get() = ok + mismatch + missing
    val allOk: Boolean get() = mismatch == 0 && missing == 0 && badLines == 0 && total > 0
}

object ChecksumManifest {

    /**
     * توليد نص بصمات بنسق GNU coreutils:
     * `<hex>␣␣<مسار-نسبي-للمجلد>` — النسق النصي (مسافتان) الذي تقرؤه كل الأدوات.
     * الملفات الفاشلة (hex = null) تُستثنى بصمت.
     */
    fun generate(results: List<HashResult?>, folder: File): String {
        val base = folder.absolutePath.trimEnd('/')
        val sb = StringBuilder()
        for (r in results) {
            val hex = r?.hex ?: continue
            val rel = r.file.absolutePath.removePrefix("$base/").removePrefix("$base")
            sb.append(hex).append("  ").append(rel.ifEmpty { r.file.name }).append('\n')
        }
        return sb.toString()
    }

    /**
     * تحليل متسامح لنص ملف بصمات:
     * - التعليقات (`#...`) والأسطر الفارغة تُتجاهل
     * - العلامة الثنائية `*` بعد المسافات تُقبل وتُوثَّق
     * - حالة السداسي مختلطة تُقبل وتُطبَّع صغيرة، CRLF يُقطع
     * - الأسطر غير القابلة للفهم تعود في badLines برقمها (1-based)
     */
    fun parse(text: String): ManifestParse {
        val entries = ArrayList<ManifestEntry>()
        val bad = ArrayList<ManifestBadLine>()
        text.lines().forEachIndexed { idx, raw ->
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            // النسق: hash مسافة(1-2) [op('*')] path
            val m = REGEX.find(line)
            if (m == null) {
                bad.add(ManifestBadLine(idx + 1, line))
                return@forEachIndexed
            }
            val hash = m.groupValues[1].lowercase()
            val binaryMarker = m.groupValues[2] == "*"
            val path = m.groupValues[3].trim()
            if (hash.isEmpty() || path.isEmpty()) {
                bad.add(ManifestBadLine(idx + 1, line))
                return@forEachIndexed
            }
            entries.add(ManifestEntry(hash, path, binaryMarker))
        }
        return ManifestParse(entries, bad)
    }

    /**
     * تحقق حقيقي لكل سطر: يُقرأ الملف ويُحسب بصمته الفعلية —
     * OK / MISMATCH (مع الفعلية) / MISSING، والأسطر المعطوبة تدخل الملخص.
     * @param resolver يحوّل المسار النسبي إلى ملف (افتراضياً داخل مجلد القاعدة)
     */
    suspend fun verify(
        parse: ManifestParse,
        algorithm: ChecksumAlgorithm,
        folder: File,
        engine: ChecksumEngine,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Pair<List<VerifyOutcome>, VerifySummary> {
        val outcomes = ArrayList<VerifyOutcome>(parse.entries.size)
        var done = 0
        for (e in parse.entries) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) break
            val f = File(folder, e.path)
            val actual = engine.hashFile(f, algorithm)?.hex
            outcomes.add(
                when {
                    actual == null -> VerifyOutcome.Missing(e)
                    actual == e.hash -> VerifyOutcome.Ok(e)
                    else -> VerifyOutcome.Mismatch(e, actual)
                },
            )
            done++
            onProgress(done, parse.entries.size)
        }
        val summary = VerifySummary(
            ok = outcomes.count { it is VerifyOutcome.Ok },
            mismatch = outcomes.count { it is VerifyOutcome.Mismatch },
            missing = outcomes.count { it is VerifyOutcome.Missing },
            badLines = parse.badLines.size,
        )
        return outcomes to summary
    }

    /** استنتاج الخوارزمية من طول أول بصمة في النص (لملفات مكشوفة تلقائياً) */
    fun detectAlgorithm(text: String): ChecksumAlgorithm? {
        val first = parse(text).entries.firstOrNull() ?: return null
        return ChecksumAlgorithm.byHexLength(first.hash)
    }

    private val REGEX = Regex("^([0-9A-Fa-f]+)[ \\t]+(\\*)?(.+)$")
}

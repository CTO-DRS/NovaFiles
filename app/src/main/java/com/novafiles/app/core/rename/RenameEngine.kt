package com.novafiles.app.core.rename

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.12 — محرك إعادة التسمية الجماعية.
 *
 * Kotlin خالص بلا أي تبعيات Android — يُختبر بالكامل على JVM.
 *
 * خط الأنابيب لكل ملف:
 *   1. فصل الامتداد (عند keepExtension)
 *   2. بحث واستبدال (حرفي أو Regex)
 *   3. تحويل حالة الأحرف
 *   4. اسم أساس جديد (اختياري) + عدّاد ترقيم
 *   5. إدراج تاريخ آخر تعديل (أو تاريخ EXIF ممرَّر خارجياً عبر dateOverrides)
 *   6. بادئة + لاحقة ثم إعادة إلحاق الامتداد
 *
 * ثم كشف التعارضات: تكرار داخل الدفعة، وجود اسم مسبقاً على القرص، أسماء غير صالحة.
 * التنفيذ على مرحلتين (اسم مؤقت ثم الاسم النهائي) لضمان عدم تصادم السلاسل داخل
 * الدفعة، مع تراجع كامل عند فشل أي عملية.
 */

/** تحويل حالة الأحرف للاسم الأساس */
enum class CaseMode { KEEP, LOWER, UPPER, CAPITALIZE }

/** حالة عنصر بعد التخطيط */
enum class RenameStatus { OK, UNCHANGED, EXISTS, DUPLICATE, INVALID }

/** خيارات إعادة التسمية */
data class RenameOptions(
    val prefix: String = "",
    val suffix: String = "",
    /** استبدال الاسم الأساسي كلياً بهذا النص (يُدمج مع العدّاد إن فُعّل) */
    val baseName: String? = null,
    val useCounter: Boolean = false,
    val counterStart: Int = 1,
    val counterStep: Int = 1,
    /** حشوة الأصفار: 3 تعني 001 */
    val counterPadding: Int = 0,
    /** فاصل بين الأجزاء المُركّبة */
    val separator: String = "_",
    val find: String = "",
    val replacement: String = "",
    val regex: Boolean = false,
    val caseMode: CaseMode = CaseMode.KEEP,
    val insertDate: Boolean = false,
    val dateFormat: String = "yyyy-MM-dd",
    val keepExtension: Boolean = true,
)

/** عنصر واحد في خطة إعادة التسمية */
data class RenameItem(
    val file: File,
    val newName: String?,
    val status: RenameStatus,
)

object RenameEngine {

    /** توليد الاسم الجديد لاسم واحد وفق الخيارات */
    fun generateName(
        originalName: String,
        counterValue: Int,
        options: RenameOptions,
        lastModified: Long = 0L,
    ): String {
        val (base, ext) = splitExtension(originalName, options.keepExtension)
        var b = base

        // بحث واستبدال
        if (options.find.isNotEmpty()) {
            b = if (options.regex) {
                runCatching { Regex(options.find).replace(b, options.replacement) }.getOrDefault(b)
            } else {
                b.replace(options.find, options.replacement)
            }
        }

        // تحويل الحالة
        b = when (options.caseMode) {
            CaseMode.KEEP -> b
            CaseMode.LOWER -> b.lowercase(Locale.ROOT)
            CaseMode.UPPER -> b.uppercase(Locale.ROOT)
            CaseMode.CAPITALIZE -> b.lowercase(Locale.ROOT).replaceFirstChar {
                if (it.isLetterOrDigit()) it.uppercaseChar() else it
            }
        }

        // اسم أساس جديد
        if (options.baseName != null) b = options.baseName

        // تركيب الأجزاء: الاسم + التاريخ + العدّاد ثم البادئة واللاحقة
        val parts = mutableListOf<String>()
        if (options.insertDate) {
            val fmt = SimpleDateFormat(options.dateFormat, Locale.US)
            parts += fmt.format(Date(if (lastModified > 0) lastModified else System.currentTimeMillis()))
        }
        if (options.useCounter) {
            parts += formatCounter(counterValue, options.counterPadding)
        }

        var name = b
        for (p in parts) {
            if (p.isEmpty()) continue
            name = if (name.isEmpty()) p else name + options.separator + p
        }
        name = options.prefix + name + options.suffix
        // تحويل الحالة يمتد للامتداد في الوضعين الصغير/الكبير
        val finalExt = when (options.caseMode) {
            CaseMode.LOWER -> ext.lowercase(Locale.ROOT)
            CaseMode.UPPER -> ext.uppercase(Locale.ROOT)
            else -> ext
        }
        return name + finalExt
    }

    /** تخطيط كامل الدفعة مع كشف التعارضات */
    fun plan(
        files: List<File>,
        options: RenameOptions,
        /** تجاوز تاريخ الملف (مثلاً تاريخ EXIF للصور) — المفتاح: المسار المطلق */
        dateOverrides: Map<String, Long> = emptyMap(),
    ): List<RenameItem> {
        val items = mutableListOf<RenameItem>()
        val seenTargets = mutableSetOf<String>()
        // الملفات التي ستُغادر أسمائها القديمة — لا تُعتبر حاجزاً على أسمائها
        val movingSources = files.map { it.absolutePath }.toSet()

        files.forEachIndexed { index, file ->
            val generated = generateName(
                originalName = file.name,
                counterValue = options.counterStart + index * options.counterStep,
                options = options,
                lastModified = dateOverrides[file.absolutePath] ?: file.lastModified(),
            )

            when {
                !isValidName(generated) -> items += RenameItem(file, generated, RenameStatus.INVALID)
                generated == file.name -> items += RenameItem(file, generated, RenameStatus.UNCHANGED)
                seenTargets.contains(generated.lowercase()) ->
                    items += RenameItem(file, generated, RenameStatus.DUPLICATE)
                else -> {
                    seenTargets += generated.lowercase()
                    val target = File(file.parentFile, generated)
                    val blocked = target.exists() && !movingSources.contains(target.absolutePath)
                    items += RenameItem(file, generated, if (blocked) RenameStatus.EXISTS else RenameStatus.OK)
                }
            }
        }
        return items
    }

    /**
     * تنفيذ الخطة على مرحلتين مع تراجع كامل عند أي فشل:
     * المرحلة 1: كل ملف OK ← اسم مؤقت فريد.
     * المرحلة 2: كل مؤقت ← الاسم النهائي.
     * عند فشل أي خطوة تُستعاد كل الأسماء المُعدَّلة وتُعاد نتيجة فشل.
     */
    fun apply(plan: List<RenameItem>): Result<Int> {
        val ok = plan.filter { it.status == RenameStatus.OK }
        if (ok.isEmpty()) return Result.success(0)

        class Staged(val original: File, val tmp: File, val final: File)

        val staged = mutableListOf<Staged>()
        val finalized = mutableListOf<Staged>() // وصلت للمرحلة 2 بنجاح

        fun revertAll() {
            // النهائيات تعود لمؤقتاتها، ثم كل المؤقتات تعود لأسمائها الأصلية
            finalized.forEach { s -> runCatching { s.final.renameTo(s.tmp) } }
            staged.forEach { s -> runCatching { if (s.tmp.exists()) s.tmp.renameTo(s.original) } }
        }

        // المرحلة 1: الأصل ← مؤقت
        for (item in ok) {
            val tmp = tempFile(item.file.parentFile, item.file.name)
            if (!item.file.renameTo(tmp)) {
                revertAll()
                return Result.failure(IllegalStateException("rename failed: ${item.file.name}"))
            }
            staged += Staged(item.file, tmp, File(item.file.parentFile, item.newName!!))
        }

        // المرحلة 2: المؤقت ← النهائي
        for (s in staged) {
            if (!s.tmp.renameTo(s.final)) {
                revertAll()
                return Result.failure(IllegalStateException("rename failed: ${s.final.name}"))
            }
            finalized += s
        }
        return Result.success(staged.size)
    }

    /** تراجع: إعادة كل عناصر OK المنفَّذة من الاسم الجديد إلى الأصلي */
    fun undo(plan: List<RenameItem>): Result<Int> = runCatching {
        var undone = 0
        for (item in plan.filter { it.status == RenameStatus.OK }) {
            val current = File(item.file.parentFile, item.newName!!)
            val original = item.file
            if (current.exists() && !original.exists()) {
                check(current.renameTo(original)) { "undo failed: ${item.newName}" }
                undone++
            }
        }
        undone
    }

    // ----- مساعدات -----

    private fun splitExtension(name: String, keep: Boolean): Pair<String, String> {
        if (!keep || !name.contains('.')) return name to ""
        val ext = "." + name.substringAfterLast('.')
        val base = name.removeSuffix(ext)
        return base to ext
    }

    internal fun formatCounter(value: Int, padding: Int): String {
        val raw = value.toString()
        if (padding <= raw.length) return raw
        return "0".repeat(padding - raw.length) + raw
    }

    private fun isValidName(name: String): Boolean {
        if (name.isBlank()) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        return true
    }

    private fun tempFile(parent: File, originalName: String): File {
        val rnd = (100000..999999).random()
        return File(parent, ".nova-rename-$rnd-$originalName")
    }
}

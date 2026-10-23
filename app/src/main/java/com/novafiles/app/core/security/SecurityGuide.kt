package com.novafiles.app.core.security

/**
 * v0.38 — توطيد ما قبل الإطلاق: الدليل الأمني للمستخدم الجديد.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * بند v1.0 من الخارطة: «دليل أمني موسّع للمستخدم الجديد». هذا المحرك
 * يحوّله إلى بنية قابلة للاختبار بدل نص حر:
 *
 *  - أقسام مرتبة حتماً بشدة معلنة (جوهرية/مستحسنة/متقدمة) ومفاتيح
 *    سلاسل ثابتة تُحصّد بالاختبار ضد strings.xml الحقيقي — لا مفتاح
 *    مفقود ممكن عند البناء.
 *  - تقدّم قراءة لكل قسم (مُقرأ/غير مُقرأ) محفوظ CSV حتماً عبر
 *    SettingsStore — والمنطق كله هنا خالص.
 *  - نسبة إنجاز + القسم التالي غير المُقرأ + إتمام كامل.
 *
 * المحرك بلا اعتماديات أندرويد: مفاتيح السلاسل نصوص، والشاشة
 * (ui/settings/SecurityGuideScreen.kt) تحلها إلى R.string.
 */

/** شدة القسم الأمني */
enum class GuideSeverity { ESSENTIAL, RECOMMENDED, ADVANCED }

/** قسم دليل واحد — المفاتيح حلُّها في الشاشة إلى موارد السلاسل */
data class GuideSection(
    val id: String,
    val order: Int, // 1..n تسلسل صارم
    val severity: GuideSeverity,
    val titleKey: String, // مفتاح strings.xml للعنوان
    val bodyKey: String,  // مفتاح strings.xml للشرح
)

/** حالة تقدّم القراءة — مجموعة أقسام مُقرأة فقط */
data class GuideProgress(val readIds: Set<String> = emptySet()) {
    val readCount: Int get() = readIds.size
}

object SecurityGuide {

    // ---------- الأقسام الثمانية — الترتيب حتمي ثابت ----------

    val SECTIONS: List<GuideSection> = listOf(
        GuideSection("vault_pin", 1, GuideSeverity.ESSENTIAL, "guide_s_vault_pin_t", "guide_s_vault_pin_b"),
        GuideSection("encryption", 2, GuideSeverity.ESSENTIAL, "guide_s_encryption_t", "guide_s_encryption_b"),
        GuideSection("recovery", 3, GuideSeverity.ESSENTIAL, "guide_s_recovery_t", "guide_s_recovery_b"),
        GuideSection("network_shares", 4, GuideSeverity.RECOMMENDED, "guide_s_network_t", "guide_s_network_b"),
        GuideSection("cloud_privacy", 5, GuideSeverity.RECOMMENDED, "guide_s_cloud_t", "guide_s_cloud_b"),
        GuideSection("permissions", 6, GuideSeverity.RECOMMENDED, "guide_s_perms_t", "guide_s_perms_b"),
        GuideSection("safe_delete", 7, GuideSeverity.ADVANCED, "guide_s_safedelete_t", "guide_s_safedelete_b"),
        GuideSection("updates", 8, GuideSeverity.ADVANCED, "guide_s_updates_t", "guide_s_updates_b"),
    )

    /** التحقق البنيوي: معرفات فريدة، تسلسل 1..n، مفاتيح غير فارغة ومتباينة */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = SECTIONS.map { it.id }
        ids.distinct().let { if (it.size != ids.size) errors += "معرفات أقسام مكررة" }
        SECTIONS.forEachIndexed { i, s ->
            if (s.order != i + 1) errors += "ترتيب القسم ${s.id} مخالف: ${s.order} والموضع ${i + 1}"
            if (s.titleKey.isBlank() || s.bodyKey.isBlank()) errors += "مفاتيح فارغة في ${s.id}"
            if (s.titleKey == s.bodyKey) errors += "مفتاحا العنوان والشرح متطابقان في ${s.id}"
        }
        SECTIONS.groupBy { it.titleKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح عنوان مكرر: $k"
        }
        SECTIONS.groupBy { it.bodyKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح شرح مكرر: $k"
        }
        return errors
    }

    fun byId(id: String): GuideSection? = SECTIONS.firstOrNull { it.id == id }

    // ---------- تقدّم القراءة ----------

    /** تحديد قسم كمُقرأ — معرف مجهول يُرفض بأمان */
    fun markRead(progress: GuideProgress, id: String): GuideProgress? =
        if (byId(id) == null) null else GuideProgress(progress.readIds + id)

    /** إلغاء قراءة قسم — معرف مجهول يُرفض بأمان */
    fun markUnread(progress: GuideProgress, id: String): GuideProgress? =
        if (byId(id) == null) null else GuideProgress(progress.readIds - id)

    /** نسبة الإنجاز 0..100 حتماً */
    fun percent(progress: GuideProgress): Int =
        if (SECTIONS.isEmpty()) 0 else progress.readIds.intersect(SECTIONS.map { it.id }.toSet()).size * 100 / SECTIONS.size

    /** هل الدليل مُتم؟ */
    fun isComplete(progress: GuideProgress): Boolean = percent(progress) == 100

    /** أول قسم غير مُقرأ بترتيب العرض — null عند الإتمام */
    fun nextUnread(progress: GuideProgress): GuideSection? =
        SECTIONS.firstOrNull { it.id !in progress.readIds }

    // ---------- ترميز CSV للتقدّم ----------

    /**
     * ترميز حتمي: مرتب بترتيب الأقسام المعروض (لا بترتيب الإدخال)،
     * والمعرفات المجهولة تُسقَط عند الترميز نفسه.
     */
    fun serialize(progress: GuideProgress): String =
        SECTIONS.filter { it.id in progress.readIds }.joinToString(",") { it.id }

    /** فك متسامح: فارغ/تالف → تقدّم صفر، ومعرفات مجهولة تُتخطّى */
    fun parse(raw: String): GuideProgress {
        if (raw.isBlank()) return GuideProgress()
        val known = SECTIONS.map { it.id }.toSet()
        return GuideProgress(
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in known }
                .toSet(),
        )
    }
}

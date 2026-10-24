package com.novafiles.app.core.onboarding

/**
 * v0.39 — جاهزية الإطلاق: محرك الترحيب للمستخدم الجديد (Onboarding).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * بند الخارطة: «دليل أمني موسّع للمستخدم الجديد» (v1.0) — الشاشة الأولى
 * التي يراها مستخدم v1.0: أربع خطوات ثابتة الترتيب تشرح (الترحيب، إذن
 * التخزين، الخزنة والأمان، الطبقة الذكية) ثم تفتح الباب للدليل الأمني
 * من v0.38. المنطق كله هنا خالص:
 *
 *  - خطوات مرتبة حتماً بمفاتيح سلاسل ثابتة تُحصَّل بالاختبار ضد
 *    strings.xml الحقيقي — لا مفتاح مفقود ممكن عند البناء.
 *  - حالة تقدّم (معرفات الخطوات المنجزة) محفوظ CSV حتماً عبر
 *    SettingsStore — بنفس نمط SecurityGuide v0.38.
 *  - «هل يُعرض الترحيب؟» قرار حتمي واحد: يُعرض فقط ما لم تُتم الخطوات
 *    كلها — إتمام مرة واحدة يعني عدم العودة أبداً (لا إزعاج تكراري).
 *
 * المحرك بلا اعتماديات أندرويد: مفاتيح السلاسل نصوص، والشاشة
 * (ui/onboarding/OnboardingScreen.kt) تحلّها إلى R.string.
 */

/** خطوة ترحيب واحدة — المفاتيح حلُّها في الشاشة إلى موارد السلاسل */
data class OnboardingStep(
    val id: String,
    val order: Int,   // 1..n تسلسل صارم
    val titleKey: String,
    val bodyKey: String,
)

/** حالة تقدّم الترحيب — مجموعة خطوات منجزة فقط */
data class OnboardingState(val doneIds: Set<String> = emptySet()) {
    val doneCount: Int get() = doneIds.size
}

object OnboardingEngine {

    // ---------- الخطوات الأربع — الترتيب حتمي ثابت ----------

    val STEPS: List<OnboardingStep> = listOf(
        OnboardingStep("welcome", 1, "onboarding_welcome_t", "onboarding_welcome_b"),
        OnboardingStep("storage", 2, "onboarding_perm_t", "onboarding_perm_b"),
        OnboardingStep("vault", 3, "onboarding_vault_t", "onboarding_vault_b"),
        OnboardingStep("smart", 4, "onboarding_smart_t", "onboarding_smart_b"),
    )

    /** التحقق البنيوي: معرفات فريدة، تسلسل 1..n، مفاتيح غير فارغة ومتباينة */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = STEPS.map { it.id }
        ids.distinct().let { if (it.size != ids.size) errors += "معرفات خطوات مكررة" }
        STEPS.forEachIndexed { i, s ->
            if (s.order != i + 1) errors += "ترتيب الخطوة ${s.id} مخالف: ${s.order} والموضع ${i + 1}"
            if (s.titleKey.isBlank() || s.bodyKey.isBlank()) errors += "مفاتيح فارغة في ${s.id}"
            if (s.titleKey == s.bodyKey) errors += "مفتاحا العنوان والشرح متطابقان في ${s.id}"
        }
        STEPS.groupBy { it.titleKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح عنوان مكرر: $k"
        }
        STEPS.groupBy { it.bodyKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح شرح مكرر: $k"
        }
        return errors
    }

    fun byId(id: String): OnboardingStep? = STEPS.firstOrNull { it.id == id }

    // ---------- تقدّم الخطوات ----------

    /** إنهاء خطوة — معرف مجهول يُرفض بأمان (null) */
    fun markDone(state: OnboardingState, id: String): OnboardingState? =
        if (byId(id) == null) null else OnboardingState(state.doneIds + id)

    /** نسبة الإنجاز 0..100 حتماً */
    fun percent(state: OnboardingState): Int =
        if (STEPS.isEmpty()) 0 else state.doneIds.intersect(STEPS.map { it.id }.toSet()).size * 100 / STEPS.size

    /** هل اكتمل الترحيب؟ (كل الخطوات المنجزة) */
    fun isComplete(state: OnboardingState): Boolean = percent(state) == 100

    /** الخطوة التالية للعرض بترتيب العرض — null عند الإتمام */
    fun nextStep(state: OnboardingState): OnboardingStep? =
        STEPS.firstOrNull { it.id !in state.doneIds }

    // ---------- قرار العرض ----------

    /** يُعرض الترحيب فقط ما لم تُتم كل الخطوات — قرار حتمي واحد */
    fun shouldShow(raw: String): Boolean = !isComplete(parse(raw))

    // ---------- ترميز CSV للحالة ----------

    /** ترميز حتمي: مرتب بترتيب الخطوات المعروضة (لا بترتيب الإدخال)، والمجهول يُسقَط */
    fun serialize(state: OnboardingState): String =
        STEPS.filter { it.id in state.doneIds }.joinToString(",") { it.id }

    /** فك متسامح: فارغ/تالف → حالة صفر، ومعرفات مجهولة تُتخطّى */
    fun parse(raw: String): OnboardingState {
        if (raw.isBlank()) return OnboardingState()
        val known = STEPS.map { it.id }.toSet()
        return OnboardingState(
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in known }
                .toSet(),
        )
    }
}

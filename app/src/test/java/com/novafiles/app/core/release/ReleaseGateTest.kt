package com.novafiles.app.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات v0.38 — بوابة جاهزية الإطلاق — JVM خالصة.
 *
 * تغطي: آلة مراحل المراجعة (منع التراجع، اشتراط المراجع والتاريخ)،
 * الدمج الحتمي، ملخص الجاهزية، بوابات الجودة (PASS/FAIL/MISSING)،
 * والمرمّز المتسامح بأسماء عربية وإيموجي.
 */
class ReleaseGateTest {

    // ---------- التحقق من سجل مراجعة ----------

    @Test
    fun `unknown language code is rejected`() {
        val errs = ReleaseGate.validate(LangReview("xx", ReviewStage.MACHINE_DRAFT))
        assertEquals(1, errs.size)
        assertTrue(errs[0].contains("xx"))
    }

    @Test
    fun `native review requires reviewer`() {
        val errs = ReleaseGate.validate(LangReview("fr", ReviewStage.NATIVE_REVIEW, reviewer = "  "))
        assertEquals(1, errs.size)
        assertTrue(errs[0].contains("المراجع"))
    }

    @Test
    fun `native review with reviewer is valid`() {
        assertTrue(ReleaseGate.validate(LangReview("fr", ReviewStage.NATIVE_REVIEW, reviewer = "لينا")).isEmpty())
    }

    @Test
    fun `sign off requires reviewer and iso date`() {
        val noDate = ReleaseGate.validate(LangReview("de", ReviewStage.SIGNED_OFF, reviewer = "عمر"))
        assertEquals(1, noDate.size)

        val badDate = ReleaseGate.validate(
            LangReview("de", ReviewStage.SIGNED_OFF, reviewer = "عمر", reviewedAt = "24/10/2026"),
        )
        assertEquals(1, badDate.size)

        val good = ReleaseGate.validate(
            LangReview("de", ReviewStage.SIGNED_OFF, reviewer = "عمر", reviewedAt = "2026-10-24"),
        )
        assertTrue(good.isEmpty())
    }

    @Test
    fun `machine draft needs nothing`() {
        assertTrue(ReleaseGate.validate(LangReview("ru", ReviewStage.MACHINE_DRAFT)).isEmpty())
    }

    // ---------- منع التراجع ----------

    @Test
    fun `stage can never go backward`() {
        assertTrue(ReleaseGate.canAdvance(ReviewStage.MACHINE_DRAFT, ReviewStage.NATIVE_REVIEW))
        assertTrue(ReleaseGate.canAdvance(ReviewStage.NATIVE_REVIEW, ReviewStage.SIGNED_OFF))
        assertTrue(ReleaseGate.canAdvance(ReviewStage.SIGNED_OFF, ReviewStage.SIGNED_OFF))
        assertFalse(ReleaseGate.canAdvance(ReviewStage.NATIVE_REVIEW, ReviewStage.MACHINE_DRAFT))
        assertFalse(ReleaseGate.canAdvance(ReviewStage.SIGNED_OFF, ReviewStage.NATIVE_REVIEW))
    }

    @Test
    fun `merge rejects downgrade with explicit arabic reason`() {
        val existing = listOf(LangReview("ar", ReviewStage.SIGNED_OFF, reviewer = "سلمى", reviewedAt = "2026-10-01"))
        val result = ReleaseGate.merge(existing, LangReview("ar", ReviewStage.MACHINE_DRAFT))
        assertTrue(result is MergeResult.Rejected)
        assertTrue((result as MergeResult.Rejected).reason.contains("تراجع"))
    }

    @Test
    fun `merge upserts and sorts deterministically`() {
        val existing = listOf(
            LangReview("ru", ReviewStage.MACHINE_DRAFT),
            LangReview("en", ReviewStage.NATIVE_REVIEW, reviewer = "John"),
        )
        val incoming = LangReview("ru", ReviewStage.NATIVE_REVIEW, reviewer = "Иван")
        val ok = ReleaseGate.merge(existing, incoming) as MergeResult.Ok
        assertEquals(listOf("en", "ru"), ok.reviews.map { it.code })
        assertEquals(ReviewStage.NATIVE_REVIEW, ok.reviews.first { it.code == "ru" }.stage)
        assertEquals("Иван", ok.reviews.first { it.code == "ru" }.reviewer)
    }

    @Test
    fun `merge rejects invalid record without touching the list`() {
        val existing = listOf(LangReview("en", ReviewStage.MACHINE_DRAFT))
        val result = ReleaseGate.merge(existing, LangReview("zz", ReviewStage.NATIVE_REVIEW))
        assertTrue(result is MergeResult.Rejected)
    }

    // ---------- ملخص الجاهزية ----------

    @Test
    fun `readiness counts signed off and lists pending sorted`() {
        val reviews = listOf(
            LangReview("ar", ReviewStage.SIGNED_OFF, reviewer = "س", reviewedAt = "2026-10-24"),
            LangReview("ru", ReviewStage.SIGNED_OFF, reviewer = "Р", reviewedAt = "2026-10-24"),
            LangReview("en", ReviewStage.NATIVE_REVIEW, reviewer = "J"),
        )
        val r = ReleaseGate.readiness(reviews)
        assertEquals(10, r.total)
        assertEquals(2, r.signedOff)
        assertFalse(r.complete)
        // الغائبة من السجل تُعد مسودة آلية — البقية مرتبة حتماً
        assertEquals(
            listOf("de", "en", "es", "fa", "fr", "id", "tr", "ur").sorted(),
            r.pendingCodes,
        )
    }

    @Test
    fun `readiness complete when all ten signed off`() {
        val all = com.novafiles.app.core.l10n.LanguageCatalog.ALL.map {
            LangReview(it.code, ReviewStage.SIGNED_OFF, reviewer = "مُراجع", reviewedAt = "2026-10-24")
        }
        val r = ReleaseGate.readiness(all)
        assertTrue(r.complete)
        assertEquals(10, r.signedOff)
        assertTrue(r.pendingCodes.isEmpty())
    }

    // ---------- بوابات الجودة ----------

    @Test
    fun `gates evaluate pass fail missing with deterministic order`() {
        val specs = listOf(
            GateSpec("jvm_tests", atLeast = 1648),
            GateSpec("apk_debug_mb", atMost = 35),
            GateSpec("cold_start_ms", atMost = 2000),
        )
        val actuals = mapOf(
            "apk_debug_mb" to 33L,
            "jvm_tests" to 1600L, // دون الأرضية — إخفاق
            // cold_start_ms غائب — MISSING
        )
        val report = QualityGates.evaluate(specs, actuals)
        assertFalse(report.passed)
        assertEquals(listOf("apk_debug_mb", "cold_start_ms", "jvm_tests"), report.statuses.map { it.id })
        assertTrue(report.statuses[0] is GateStatus.Pass)
        assertTrue(report.statuses[1] is GateStatus.Missing)
        assertTrue(report.statuses[2] is GateStatus.Fail)
        assertEquals(2, report.blockers.size)
    }

    @Test
    fun `gates pass fully when all thresholds met`() {
        val specs = listOf(
            GateSpec("jvm_tests", atLeast = 1648),
            GateSpec("apk_debug_mb", atMost = 35),
        )
        val report = QualityGates.evaluate(
            specs,
            mapOf("jvm_tests" to 1653L, "apk_debug_mb" to 35L), // الحد مسموح بحدّه
        )
        assertTrue(report.passed)
        assertTrue(report.blockers.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gate spec without any threshold is forbidden`() {
        GateSpec("x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `gate spec with both directions is forbidden`() {
        GateSpec("x", atLeast = 1, atMost = 5)
    }

    // ---------- المرمّز المتسامح ----------

    @Test
    fun `codec round trips arabic reviewer names and notes`() {
        val reviews = listOf(
            LangReview("ar", ReviewStage.SIGNED_OFF, reviewer = "سلمى \"المراجعة\"", reviewedAt = "2026-10-24", notes = "سطر أول\nسطر ثانٍ"),
            LangReview("ur", ReviewStage.NATIVE_REVIEW, reviewer = "احمد 🌙", notes = "تبويب ⇦ صحيح"),
            LangReview("en", ReviewStage.MACHINE_DRAFT),
        )
        val decoded = LangReviewCodec.decode(LangReviewCodec.encode(reviews))
        assertEquals(3, decoded.size)
        assertEquals(reviews.sortedBy { it.code }, decoded.sortedBy { it.code })
        val ar = decoded.first { it.code == "ar" }
        assertEquals("سلمى \"المراجعة\"", ar.reviewer)
        assertEquals("سطر أول\nسطر ثانٍ", ar.notes)
    }

    @Test
    fun `codec output is deterministic and sorted by code`() {
        val a = listOf(LangReview("en", ReviewStage.MACHINE_DRAFT), LangReview("ar", ReviewStage.MACHINE_DRAFT))
        val b = listOf(LangReview("ar", ReviewStage.MACHINE_DRAFT), LangReview("en", ReviewStage.MACHINE_DRAFT))
        assertEquals(LangReviewCodec.encode(a), LangReviewCodec.encode(b))
        assertTrue(LangReviewCodec.encode(a).startsWith("""[{"code":"ar""""))
    }

    @Test
    fun `codec tolerates corrupt input and skips unknown stages`() {
        assertEquals(emptyList<LangReview>(), LangReviewCodec.decode("not json at all"))
        assertEquals(emptyList<LangReview>(), LangReviewCodec.decode(""))
        assertEquals(emptyList<LangReview>(), LangReviewCodec.decode("{broken"))

        // مرحلة مجهولة (توافق أمامي من إصدار أحدث) تُتخطى والبقية تُفك
        val mixed = """[{"code":"ar","stage":"SIGNED_OFF","reviewer":"س","date":"2026-10-24","notes":""},{"code":"en","stage":"QUANTUM_REVIEW","reviewer":"x","date":"","notes":""}]"""
        val decoded = LangReviewCodec.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals("ar", decoded[0].code)
    }

    @Test
    fun `codec keeps parseable prefix of a truncated tail`() {
        val good = """{"code":"ar","stage":"MACHINE_DRAFT","reviewer":"","date":"","notes":""}"""
        val truncated = """[${good},{"code":"en","stage":"MAC"""
        val decoded = LangReviewCodec.decode(truncated)
        assertEquals(1, decoded.size)
        assertEquals("ar", decoded[0].code)
    }
}

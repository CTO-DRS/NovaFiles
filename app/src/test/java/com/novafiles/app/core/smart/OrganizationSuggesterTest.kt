package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات المرحلة B — محرك التنظيم الحتمي:
 * حفظ الذيل، العتبات الدنيا، تصاعد الثقة وسقفها، الأهداف الأربعة،
 * عتبات الأرشيف/المثبّت، علامات ORGTYPE، التجاهل النهائي،
 * تصادم الأسماء، «منظم أصلاً»، والترتيب الحتمي بالأولوية.
 */
class OrganizationSuggesterTest {

    private val now = 1_800_000_000_000L
    private val dl = "/storage/emulated/0/Download"

    private fun file(parent: String, name: String, ageDays: Long = 1) = SnapshotRow(
        path = "$parent/$name",
        name = name,
        parent = parent,
        size = 1024L,
        modified = now - ageDays * DownloadClassifier.DAY_MS,
        isDirectory = false,
    )

    // ── 1) حفظ الذيل: الاسم يبقى حرفياً والهدف = دليل قياسي داخل الأب ──
    @Test
    fun `tail preserved - move keeps exact file name`() {
        val report = OrganizationSuggester.suggest(
            listOf(file(dl, "setup.apk", ageDays = 40)),
            now,
        )
        val s = report.suggestions.single()
        val move = s.moves.single()
        assertEquals("setup.apk", move.fileName)
        assertEquals("$dl/setup.apk", move.sourcePath)
        assertEquals("$dl/Installers/setup.apk", move.destinationPath)
    }

    // ── 2) العتبات الدنيا: تحت العتبة لا اقتراح ──
    @Test
    fun `below thresholds - no suggestions`() {
        val report = OrganizationSuggester.suggest(
            listOf(
                file(dl, "doc.pdf"),                       // مستند واحد < 3
                file(dl, "a.zip"), file(dl, "b.zip"),      // أرشيفان... (عتبة الأرشيف 2 — انظر 5)
                file(dl, "song.mp3"),                      // وسائط واحدة < 3
            ),
            now,
        )
        assertNull(report.suggestions.firstOrNull { it.rule == OrgRule.LOOSE_DOCUMENTS })
        assertNull(report.suggestions.firstOrNull { it.rule == OrgRule.LOOSE_MEDIA })
        // الأرشيفان يمثلان عتبة LOOSE_ARCHIVES (2) — اقتراح واحد فقط لهما
        assertEquals(1, report.suggestions.size)
    }

    // ── 3) تصاعد الثقة وسقفها: [0.50, 0.95] حتمي ──
    @Test
    fun `confidence escalates with size and caps at 0_95`() {
        assertEquals(0.50, OrganizationSuggester.confidenceFor(3, 3), 1e-9)
        assertEquals(0.60, OrganizationSuggester.confidenceFor(5, 3), 1e-9)
        assertEquals(0.95, OrganizationSuggester.confidenceFor(100, 3), 1e-9)
        assertEquals(0.95, OrganizationSuggester.confidenceFor(1000, 3), 1e-9)
        // الحدود الدنيا للتصنيف نفسها
        val few = (1..3).map { file(dl, "m$it.jpg") }
        val more = (1..6).map { file(dl, "n$it.jpg") }
        val cFew = OrganizationSuggester.suggest(few, now).suggestions.single().confidence
        val cMore = OrganizationSuggester.suggest(more, now).suggestions.single().confidence
        assertTrue(cFew == 0.50 && cMore == 0.65)
    }

    // ── 4) الأهداف الأربعة: كل قاعدة تستهدف دليلها القياسي ──
    @Test
    fun `four rules target their standard directories`() {
        val rows = listOf(
            file(dl, "setup.apk", ageDays = 40),
            file(dl, "pack.zip"), file(dl, "pack2.zip"),
            file(dl, "d1.pdf"), file(dl, "d2.pdf"), file(dl, "d3.pdf"),
            file(dl, "v1.mp4"), file(dl, "v2.mp4"), file(dl, "v3.mp4"),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        assertEquals(
            setOf("Installers", "Archives", "Documents", "Media"),
            report.suggestions.map { it.targetDir.substringAfterLast('/') }.toSet(),
        )
    }

    // ── 5) عتبات الأرشيف (≥2) والمثبّت (≥1 بعمر 30 يوماً) ──
    @Test
    fun `archive needs two - installer needs one aged thirty days`() {
        // أرشيف واحد: لا شيء
        assertEquals(0, OrganizationSuggester.suggest(listOf(file(dl, "solo.zip")), now).suggestions.size)
        // أرشيفان: اقتراح
        assertEquals(
            OrgRule.LOOSE_ARCHIVES,
            OrganizationSuggester.suggest(listOf(file(dl, "a.zip"), file(dl, "b.zip")), now)
                .suggestions.single().rule,
        )
        // مثبّت طازج (يومان): لا شيء
        assertEquals(0, OrganizationSuggester.suggest(listOf(file(dl, "fresh.apk", ageDays = 2)), now).suggestions.size)
        // مثبّت عمره 30 يوماً بالضبط: اقتراح
        assertEquals(
            OrgRule.OLD_INSTALLERS,
            OrganizationSuggester.suggest(listOf(file(dl, "old.apk", ageDays = 30)), now)
                .suggestions.single().rule,
        )
    }

    // ── 6) علامات ORGTYPE: تخطي الدليل كلياً امتناعاً موثّقاً ──
    @Test
    fun `ORGTYPE marker skips the whole directory`() {
        val rows = listOf(
            file(dl, "ORGTYPE:.manual"),
            file(dl, "a.zip"), file(dl, "b.zip"),
            file(dl, "d1.pdf"), file(dl, "d2.pdf"), file(dl, "d3.pdf"),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        assertEquals(0, report.suggestions.size)
        val ab = report.abstentions.single()
        assertEquals("org_abstain_marker", ab.code)
        assertEquals(dl, ab.parentDir)
        assertEquals("ORGTYPE:.manual", ab.detail)
    }

    // ── 7) التجاهل النهائي: مفتاح dismissed لا يعود يُقترح أبداً ──
    @Test
    fun `dismissed key is filtered permanently`() {
        val rows = listOf(file(dl, "old.apk", ageDays = 40))
        val key = OrganizationSuggester.suggest(rows, now).suggestions.single().key
        assertTrue(key.startsWith("ORG:OLD_INSTALLERS:$dl:"))
        val after = OrganizationSuggester.suggest(rows, now, dismissedKeys = setOf(key))
        assertEquals(0, after.suggestions.size)
        // والامتناعات لا تتأثر بالتجاهل (الامتناع ليس اقتراحاً مرفوضاً)
        assertEquals(0, after.abstentions.size)
    }

    // ── 8) تصادم الأسماء داخل المجموعة يمنع الاقتراح امتناعاً موثّقاً ──
    @Test
    fun `name collision abstains with conflict`() {
        val rows = listOf(
            file(dl, "doc.pdf"),
            file("/storage/emulated/0/Download/sub", "doc.pdf"),  // دليل آخر — لا تصادم هنا
        )
        val other = OrganizationSuggester.suggest(rows, now)
        assertEquals(0, other.abstentions.size)

        // تصادم حقيقي: نفس الاسم مرتين في دليل واحد (من مصدرين مختلفين للفهرس)
        val clash = listOf(
            file(dl, "old.apk", ageDays = 40),
            SnapshotRow("$dl/old.apk", "old.apk", dl, 2048L, now - 40 * DownloadClassifier.DAY_MS, false),
        )
        val report = OrganizationSuggester.suggest(clash, now)
        assertEquals(0, report.suggestions.size)
        assertEquals("org_abstain_conflict", report.abstentions.single().code)
        assertEquals("old.apk", report.abstentions.single().detail)
    }

    // ── 9) «منظم أصلاً»: دليل اسمه اسم هدف قياسي يُترك امتناعاً ──
    @Test
    fun `directory named as standard target is left alone`() {
        val archives = "/storage/emulated/0/Archives"
        val rows = listOf(
            file(archives, "a.zip"), file(archives, "b.zip"),
            file(archives, "d1.pdf"), file(archives, "d2.pdf"), file(archives, "d3.pdf"),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        assertEquals(0, report.suggestions.size)
        assertEquals("org_abstain_already", report.abstentions.single().code)
        assertEquals("Archives", report.abstentions.single().detail)
    }

    // ── 10) الترتيب الحتمي بالأولوية 1→4 ثم المسار ──
    @Test
    fun `ordering is deterministic by rule priority then parent`() {
        val other = "/storage/emulated/0/BLUETOOTH"
        val rows = listOf(
            // الأدلة بترتيب معكوس في الإدخال
            file(other, "v1.mp4"), file(other, "v2.mp4"), file(other, "v3.mp4"),
            file(dl, "d1.pdf"), file(dl, "d2.pdf"), file(dl, "d3.pdf"),
            file(other, "a.zip"), file(other, "b.zip"),
            file(dl, "setup.apk", ageDays = 40),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        val prios = report.suggestions.map { it.rule.priority }
        assertEquals(listOf(1, 2, 3, 4), prios)
        assertEquals(dl, report.suggestions[0].parentDir)
        assertEquals(other, report.suggestions[1].parentDir)
        // إعادة نفس الإدخال بترتيب مختلف = الناتج نفسه حرفياً
        val shuffled = OrganizationSuggester.suggest(rows.shuffled(java.util.Random(42)), now)
        assertEquals(report, shuffled)
    }

    // ── 11) لا إعادة تسمية إطلاقاً: كل نقل destination = الهدف + الذيل الأصلي ──
    @Test
    fun `no renames - every destination is target plus original tail`() {
        val rows = listOf(
            file(dl, "x1.mp4"), file(dl, "x2.mp4"), file(dl, "x3.mp4"),
            file(dl, "حصرياً.mkv"),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        for (s in report.suggestions) {
            for (m in s.moves) {
                assertEquals(m.fileName, m.sourcePath.substringAfterLast('/'))
                assertEquals(m.destinationPath, "${s.targetDir}/${m.fileName}")
            }
        }
    }

    // ── 12) العلامة تحجب دليلها فقط — الأدلة الأخرى تعمل ──
    @Test
    fun `marker blocks only its own directory`() {
        val other = "/storage/emulated/0/Movies"
        val rows = listOf(
            file(dl, "ORGTYPE:.manual"),
            file(dl, "a.zip"), file(dl, "b.zip"),
            file(other, "c.zip"), file(other, "d.zip"),
        )
        val report = OrganizationSuggester.suggest(rows, now)
        assertEquals(1, report.suggestions.size)
        assertEquals(other, report.suggestions.single().parentDir)
        assertEquals("org_abstain_marker", report.abstentions.single().code)
    }

    // ── 13) الحد الأقصى والتجاهل الجماعي: take(limit) ومفاتيح متعددة ──
    @Test
    fun `limit and multi-dismissal respect deterministic keys`() {
        val rows = mutableListOf<SnapshotRow>()
        for (i in 1..5) rows += file("$dl/d$i", "old$i.apk", ageDays = 40)
        val full = OrganizationSuggester.suggest(rows, now)
        assertEquals(5, full.suggestions.size)
        val limited = OrganizationSuggester.suggest(rows, now, limit = 3)
        assertEquals(3, limited.suggestions.size)
        assertEquals(full.suggestions.take(3), limited.suggestions)
        val dismissed = OrganizationSuggester.suggest(
            rows, now,
            dismissedKeys = full.suggestions.map { it.key }.toSet(),
        )
        assertEquals(0, dismissed.suggestions.size)
        assertFalse(dismissed.abstentions.isNotEmpty())
    }
}

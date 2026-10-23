package com.novafiles.app.core.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.38 — بوابة تدقيق الإتاحة — JVM خالصة.
 *
 * أهم اختبار هنا هو «اختبار المستودع الحقيقي»: يمسح مصادر الواجهة
 * الفعلية ويقارنها بالقائمة البيضاء المُراجَعة — أي
 * `contentDescription = null` جديد غير مُبرَّر يُفشل البناء حتى
 * تُراجَع إتاحته. هذا بند «مسار إتاحة كامل» من الخارطة محمياً بالاختبار.
 */
class A11ySourceAuditTest {

    // ---------- العدّ ----------

    @Test
    fun `counts exact literal occurrences only`() {
        assertEquals(3, A11ySourceAudit.countOccurrences("a(contentDescription = null) b(contentDescription = null) c(contentDescription = null)"))
        assertEquals(0, A11ySourceAudit.countOccurrences("contentDescription = \"وصف\""))
        assertEquals(0, A11ySourceAudit.countOccurrences("contentDescription=null")) // بلا فراغات — لا يطابق نمط المستودع
    }

    // ---------- منطق التدقيق ----------

    @Test
    fun `unlisted file with occurrences fails with kind unlisted`() {
        val findings = A11ySourceAudit.audit(
            mapOf("new/NewScreen.kt" to "Icon(..., contentDescription = null)"),
            emptyList(),
        )
        assertEquals(1, findings.size)
        assertEquals(SourceFinding.Kind.UNLISTED, findings[0].kind)
        assertTrue(findings[0].detail.contains("تبرير"))
    }

    @Test
    fun `exceeding the allowed count fails`() {
        val allow = listOf(A11yAllow("old/Screen.kt", 1, "مبرر مُراجَع"))
        val findings = A11ySourceAudit.audit(
            mapOf("old/Screen.kt" to "x(contentDescription = null) y(contentDescription = null)"),
            allow,
        )
        assertEquals(1, findings.size)
        assertEquals(SourceFinding.Kind.EXCEEDS, findings[0].kind)
        assertTrue(findings[0].detail.contains("الفعلي 2"))
        assertTrue(findings[0].detail.contains("المسموح 1"))
    }

    @Test
    fun `stale allowlist entry is flagged for cleanup`() {
        val allow = listOf(A11yAllow("old/Screen.kt", 3, "قديم"))
        val findings = A11ySourceAudit.audit(
            mapOf("old/Screen.kt" to "x(contentDescription = null)"),
            allow,
        )
        assertEquals(1, findings.size)
        assertEquals(SourceFinding.Kind.STALE, findings[0].kind)
    }

    @Test
    fun `allowlist entry whose file lost all occurrences is stale`() {
        val allow = listOf(A11yAllow("gone/Screen.kt", 2, "حُذف الموضع"))
        val findings = A11ySourceAudit.audit(mapOf("other/Screen.kt" to "icon(contentDescription = null)"), allow)
        // UNLISTED لملف آخر + STALE لإدخال ملف لم يعد فيه مواضع
        assertEquals(2, findings.size)
        assertEquals(
            listOf(SourceFinding.Kind.STALE, SourceFinding.Kind.UNLISTED).sortedBy { it.name },
            findings.map { it.kind }.sortedBy { it.name },
        )
    }

    @Test
    fun `clean pass when counts exactly match the reviewed allowlist`() {
        val allow = listOf(A11yAllow("a/One.kt", 2, "مبرر"), A11yAllow("b/Two.kt", 1, "مبرر"))
        val findings = A11ySourceAudit.audit(
            mapOf(
                "a/One.kt" to "x(contentDescription = null) y(contentDescription = null)",
                "b/Two.kt" to "z(contentDescription = null)",
                "c/None.kt" to "contentDescription = \"وصف\"",
            ),
            allow,
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun `findings are deterministically ordered`() {
        val findings = A11ySourceAudit.audit(
            mapOf(
                "z/Last.kt" to "contentDescription = null",
                "a/First.kt" to "contentDescription = null",
            ),
            emptyList(),
        )
        assertEquals(listOf("a/First.kt", "z/Last.kt"), findings.map { it.path })
    }

    // ═════════ اختبار المستودع الحقيقي — البوابة الفعلية ═════════

    /** جذر حزمة الواجهة الحقيقي من مجلد تشغيل الاختبار */
    private fun resolveUiDir(): File {
        val rel = listOf(
            "src/main/java/com/novafiles/app/ui",
            "app/src/main/java/com/novafiles/app/ui",
        )
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (r in rel) {
                val f = File(d, r)
                if (f.isDirectory) return f
            }
            dir = d.parentFile
        }
        error("مجلد ui غير موجود من ${System.getProperty("user.dir")}")
    }

    @Test
    fun `real repo sources match the reviewed allowlist exactly`() {
        val ui = resolveUiDir()
        val sources = mutableMapOf<String, String>()
        ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(ui).infixSeparators()
                sources[rel] = f.readText()
            }
        assertTrue("فحص سليم: يلزم وجود ملفات واجهة", sources.size > 40)

        val findings = A11ySourceAudit.audit(sources, A11ySourceAudit.ALLOW_V038)
        assertTrue(
            "انحدار إتاحة — راجع وأدرجه في القائمة البيضاء مع تبرير:\n" +
                findings.joinToString("\n") { "${it.path} [${it.kind}] ${it.detail}" },
            findings.isEmpty(),
        )
    }

    @Test
    fun `allowlist total matches the real repo count`() {
        val ui = resolveUiDir()
        var total = 0
        ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { total += A11ySourceAudit.countOccurrences(it.readText()) }
        assertEquals(A11ySourceAudit.ALLOW_V038.sumOf { it.allowed }, total)
    }

    private fun File.infixSeparators(): String = path.replace('\\', '/')
}

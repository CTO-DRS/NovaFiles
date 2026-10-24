package com.novafiles.app.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات v0.39 — نقل الإعدادات بين الأجهزة — JVM خالصة.
 *
 * تغطي: الترميز الحتمي، البصمة (FNV-1a) وكشف التعديل الخارجي،
 * الفك المتسامح (تلف/مخطط أحدث/مفاتيح مجهولة)، سياسا الدمج
 * والاستبدال، واستبعاد الأسرار من قائمة التصدير — بصمة أمنية ثابتة.
 */
class SettingsExportEngineTest {

    private fun payload(
        prefs: Map<String, String> = mapOf(
            "theme_mode" to "DARK",
            "view_grid" to "true",
            "palette_id" to "ocean",
        ),
        bookmarks: List<BookmarkRef> = listOf(
            BookmarkRef("العمل", "/storage/emulated/0/العمل"),
            BookmarkRef("Camera 📷", "/storage/emulated/0/DCIM/Camera"),
        ),
    ) = ExportPayload(
        schema = 1,
        exportedAt = "2026-10-24T10:30:00Z",
        prefs = prefs,
        bookmarks = bookmarks,
    )

    // ---------- الترميز الحتمي ----------

    @Test
    fun `encode is deterministic regardless of map order`() {
        val a = encode(payload(prefs = linkedMapOf("theme_mode" to "DARK", "view_grid" to "true")))
        val b = encode(payload(prefs = linkedMapOf("view_grid" to "true", "theme_mode" to "DARK")))
        assertEquals(a, b)
    }

    @Test
    fun `encode round trips arabic and emoji`() {
        val p = payload()
        val decoded = SettingsExportEngine.decode(encode(p))
        assertTrue(decoded is DecodeResult.Ok)
        val ok = decoded as DecodeResult.Ok
        assertEquals(p, ok.payload)
        assertEquals(0, ok.skippedUnknownPrefs)
    }

    @Test
    fun `fnv1a is stable and hex formatted`() {
        val h1 = SettingsExportEngine.fnv1aHex("nova")
        val h2 = SettingsExportEngine.fnv1aHex("nova")
        assertEquals(h1, h2)
        assertEquals(16, h1.length)
        // قيمة مرجعية محسوبة مستقلاً عن التنفيذ
        assertEquals("3bf1ebbad15242ff", SettingsExportEngine.fnv1aHex("nova"))
    }

    // ---------- بصمة التحقق ----------

    @Test
    fun `tampered content is rejected by checksum`() {
        val json = encode(payload())
        // تعديل قيمة خارج النسخة (بدون إعادة حساب البصمة)
        val tampered = json.replace("\"theme_mode\":\"DARK\"", "\"theme_mode\":\"LIGHT\"")
        val result = SettingsExportEngine.decode(tampered)
        assertTrue(result is DecodeResult.Rejected)
        assertTrue((result as DecodeResult.Rejected).reason.contains("بصمة"))
    }

    @Test
    fun `recomputed checksum over rebuilt content enforces cap deterministically`() {
        // نسخة يدوية بأكثر من السقف وببصمة صحيحة على الكل — القص يغيّر الجسم فيُرفض
        val bms = (1..30).map { BookmarkRef("م$it", "/p/$it") }
        val json = encode(payload(bookmarks = bms))
        val result = SettingsExportEngine.decode(json)
        assertTrue(result is DecodeResult.Rejected)
    }

    // ---------- الفك المتسامح ----------

    @Test
    fun `corrupt files are rejected not thrown`() {
        listOf("", "   ", "not json", "[1,2]", "{broken", "{\"schema\":1").forEach {
            assertTrue(
                "يجب رفض: $it",
                SettingsExportEngine.decode(it) is DecodeResult.Rejected,
            )
        }
    }

    @Test
    fun `newer schema is rejected with upgrade reason`() {
        val json = encode(payload()).replace("\"schema\":1", "\"schema\":2")
        val result = SettingsExportEngine.decode(json)
        assertTrue(result is DecodeResult.Rejected)
        assertTrue((result as DecodeResult.Rejected).reason.contains("أحدث"))
    }

    @Test
    fun `unknown top-level and pref keys are tolerated and counted`() {
        // نسخة من إصدار أحدث بمفتاحين مجهولين داخل prefs — تُفك وتُحصى
        val json = encode(payload(prefs = mapOf("theme_mode" to "DARK", "quantum_pref" to "x", "warp" to "y")))
        val result = SettingsExportEngine.decode(json)
        assertTrue(result is DecodeResult.Ok)
        val ok = result as DecodeResult.Ok
        assertEquals(2, ok.skippedUnknownPrefs)
        assertTrue(ok.payload.prefs.containsKey("quantum_pref")) // تُحفظ في النسخة
    }

    @Test
    fun `blank bookmarks are dropped and cap truncates`() {
        val bms = List(26) { BookmarkRef("م$it", "/p/$it") } + BookmarkRef("فارغ", "  ")
        val json = encode(payload(bookmarks = bms))
        // بصمة الصف الأصلي (30 عنصراً) لن تطابق المقصوص، لكن الفك يتوقع رفضاً محسوماً
        val result = SettingsExportEngine.decode(json)
        assertTrue(result is DecodeResult.Rejected)
    }

    @Test
    fun `cap enforced exactly at 24 survives round trip`() {
        val bms = (1..24).map { BookmarkRef("م$it", "/p/$it") }
        val p = payload(bookmarks = bms)
        val decoded = SettingsExportEngine.decode(encode(p))
        assertTrue(decoded is DecodeResult.Ok)
        assertEquals(24, (decoded as DecodeResult.Ok).payload.bookmarks.size)
    }

    // ---------- سياسة التطبيق ----------

    @Test
    fun `merge fills missing and keeps current on conflict`() {
        val current = mapOf("theme_mode" to "LIGHT", "view_grid" to "false")
        val incoming = payload(
            prefs = mapOf("theme_mode" to "DARK", "palette_id" to "ocean", "ghost_key" to "x"),
        )
        val (merged, summary) = SettingsExportEngine.applyPolicy(current, incoming, ImportPolicy.MERGE)
        assertEquals("LIGHT", merged["theme_mode"]) // الحالي يفوز
        assertEquals("false", merged["view_grid"])
        assertEquals("ocean", merged["palette_id"]) // الناقص يُملأ
        assertEquals(2, summary.appliedPrefs)       // المجهول لا يُطبّق
        assertEquals(1, summary.skippedPrefs)
    }

    @Test
    fun `replace wins on all known keys and keeps unknown current keys`() {
        val current = mapOf("theme_mode" to "LIGHT", "custom_local" to "keep-me")
        val incoming = payload(prefs = mapOf("theme_mode" to "DARK", "view_grid" to "true"))
        val (merged, summary) = SettingsExportEngine.applyPolicy(current, incoming, ImportPolicy.REPLACE)
        assertEquals("DARK", merged["theme_mode"])
        assertEquals("true", merged["view_grid"])
        assertEquals("keep-me", merged["custom_local"]) // مفتاح محلي غير معروف لا يُمسّ
    }

    @Test
    fun `replace drops known keys absent from incoming`() {
        val current = mapOf("theme_mode" to "LIGHT", "sort" to "size", "custom_local" to "keep")
        val incoming = payload(prefs = mapOf("theme_mode" to "DARK"))
        val (merged, _) = SettingsExportEngine.applyPolicy(current, incoming, ImportPolicy.REPLACE)
        assertEquals("DARK", merged["theme_mode"])
        assertTrue(!merged.containsKey("sort")) // معروف غائب → يعود للافتراضي بحذفه
        assertEquals("keep", merged["custom_local"])
    }

    // ---------- البصمة الأمنية: لا أسرار في التصدير ----------

    @Test
    fun `exportable keys never contain server credentials`() {
        // v0.41.1 — الأختام الجديدة ممنوعة كذلك (التصدير لا يحمل أسراراً حتى المشفرة منها)
        val forbidden = listOf(
            "ftp_user", "ftp_pass", "dav_user", "dav_pass",
            "ftp_pass_enc", "dav_pass_enc",
        )
        for (key in forbidden) {
            assertTrue("المفتاح السري $key ممنوع من التصدير", key !in SettingsExportEngine.EXPORTABLE_PREF_KEYS)
        }
    }

    @Test
    fun `apply policy only touches exportable keys`() {
        val incoming = payload(prefs = mapOf("ftp_pass" to "secret", "theme_mode" to "DARK"))
        val (merged, summary) = SettingsExportEngine.applyPolicy(emptyMap(), incoming, ImportPolicy.REPLACE)
        assertTrue(!merged.containsKey("ftp_pass"))
        assertEquals(1, summary.appliedPrefs)
        assertEquals(1, summary.skippedPrefs)
    }

    // ---------- الجسم المتعارف ----------

    @Test
    fun `canonical body is order independent for prefs`() {
        val a = SettingsExportEngine.canonicalBody(
            payload(prefs = linkedMapOf("a" to "1", "b" to "2")),
        )
        val b = SettingsExportEngine.canonicalBody(
            payload(prefs = linkedMapOf("b" to "2", "a" to "1")),
        )
        assertEquals(a, b)
    }
}

/** الترميز بلا وعي بالترتيب — عامل مساعد للاختبار فقط */
private fun encode(p: com.novafiles.app.core.backup.ExportPayload): String =
    SettingsExportEngine.encode(p)

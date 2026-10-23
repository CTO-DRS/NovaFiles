package com.novafiles.app.core.rules

import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.fs.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات ترميز القواعد JSON: دوار كامل، تهريب عربي/إيموجي، تسامح مع العطب */
class RuleCodecTest {

    private fun sampleRule() = WatchRule(
        id = "r-01",
        name = "صور واتساب",
        watchFolder = "/storage/emulated/0/Download",
        conditions = RuleConditions(
            nameGlob = "IMG_*.jpg",
            types = setOf(FileType.IMAGE, FileType.VIDEO),
            minSizeBytes = 1024L,
            maxSizeBytes = 1048576L,
        ),
        actions = listOf(
            RuleAction.MoveTo("/storage/emulated/0/Pictures/whatsapp"),
            RuleAction.ArchiveZip("WhatsApp-{date}", append = true, deleteAfter = false),
            RuleAction.Checksum(ChecksumAlgorithm.SHA256),
            RuleAction.TagFile("مهم"),
            RuleAction.UploadCloud(42L, "/NovaFiles-Docs"),
        ),
        enabled = true,
        dryRun = false,
        createdAt = 1_760_529_600_000L,
        lastRunAt = 1_760_600_000_000L,
        runCount = 7,
    )

    @Test
    fun `full roundtrip preserves every field`() {
        val r = sampleRule()
        val back = RuleCodec.decode(RuleCodec.encode(listOf(r)))
        assertEquals(listOf(r), back)
    }

    @Test
    fun `roundtrip of all five action kinds keeps order`() {
        val back = RuleCodec.decode(RuleCodec.encode(listOf(sampleRule()))).first()
        assertEquals(5, back.actions.size)
        assertTrue(back.actions[0] is RuleAction.MoveTo)
        assertTrue(back.actions[1] is RuleAction.ArchiveZip)
        assertTrue(back.actions[2] is RuleAction.Checksum)
        assertTrue(back.actions[3] is RuleAction.TagFile)
        assertTrue(back.actions[4] is RuleAction.UploadCloud)
    }

    @Test
    fun `arabic and emoji names survive escaping`() {
        val r = WatchRule(
            id = "r2", name = "تقارير 📊 \"مهمة\"",
            watchFolder = "/storage/محتوى عربي (1)/📁",
            conditions = RuleConditions(nameGlob = "تقرير*"),
            actions = listOf(RuleAction.TagFile("وسم \n متعدد الأسطر\t_TAB")),
        )
        val back = RuleCodec.decode(RuleCodec.encode(listOf(r)))
        assertEquals(listOf(r), back)
    }

    @Test
    fun `escaping emits no raw control chars`() {
        val r = WatchRule("r", "a\nb\"c\\d", "/w", actions = listOf(RuleAction.TagFile("x\u0001y")))
        val json = RuleCodec.encode(listOf(r))
        assertFalse(json.contains("\n"))
        assertTrue(json.contains("\\n"))
        assertTrue(json.contains("\\u0001"))
    }

    @Test
    fun `null lastRunAt and empty conditions roundtrip`() {
        val r = WatchRule(id = "r3", name = "n", watchFolder = "/w")
        val back = RuleCodec.decode(RuleCodec.encode(listOf(r)))
        assertEquals(listOf(r), back)
        assertNull(back[0].lastRunAt)
    }

    @Test
    fun `corrupt json decodes to empty list`() {
        assertEquals(emptyList<WatchRule>(), RuleCodec.decode("not json at all"))
        assertEquals(emptyList<WatchRule>(), RuleCodec.decode("{\"broken\":"))
        assertEquals(emptyList<WatchRule>(), RuleCodec.decode(""))
    }

    @Test
    fun `json array of non-objects decodes to empty`() {
        assertEquals(emptyList<WatchRule>(), RuleCodec.decode("[1,2,3]"))
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val back = RuleCodec.decode("""[{"id":"x"}]""")
        assertEquals(1, back.size)
        assertEquals("x", back[0].id)
        assertEquals("", back[0].name)
        assertTrue(back[0].enabled)
        assertFalse(back[0].dryRun)
        assertEquals(0, back[0].runCount)
        assertNull(back[0].lastRunAt)
        assertTrue(back[0].actions.isEmpty())
    }

    @Test
    fun `unknown action kind is skipped not crash`() {
        val json = """[{"id":"x","actions":[{"k":"teleport","to":"nowhere"},{"k":"move","to":"/t"}]}]"""
        val back = RuleCodec.decode(json)
        assertEquals(1, back.size)
        assertEquals(listOf<RuleAction>(RuleAction.MoveTo("/t")), back[0].actions)
    }

    @Test
    fun `unknown type name is dropped`() {
        val json = """[{"id":"x","cond":{"types":["IMAGE","HOLOGRAM"]}}]"""
        val back = RuleCodec.decode(json)
        assertEquals(setOf(FileType.IMAGE), back[0].conditions.types)
    }

    @Test
    fun `disabled rule keeps disabled flag`() {
        val r = WatchRule(id = "r4", name = "n", watchFolder = "/w", enabled = false)
        assertFalse(RuleCodec.decode(RuleCodec.encode(listOf(r)))[0].enabled)
    }

    @Test
    fun `multiple rules keep their order`() {
        val a = WatchRule(id = "a", name = "أول", watchFolder = "/1")
        val b = WatchRule(id = "b", name = "ثانٍ", watchFolder = "/2")
        val back = RuleCodec.decode(RuleCodec.encode(listOf(a, b)))
        assertEquals(listOf("a", "b"), back.map { it.id })
        assertEquals(listOf("أول", "ثانٍ"), back.map { it.name })
    }

    @Test
    fun `encode produces valid json readable by MiniJson`() {
        val json = RuleCodec.encode(listOf(sampleRule()))
        val v = com.novafiles.app.core.cloud.MiniJson.parse(json)
        assertTrue(v is com.novafiles.app.core.cloud.MiniJson.Value.Arr)
    }
}

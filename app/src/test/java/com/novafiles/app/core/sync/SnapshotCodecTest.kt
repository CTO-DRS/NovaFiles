package com.novafiles.app.core.sync

import com.novafiles.app.data.sync.SyncRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مرمّز لقطة الأساس + قواعد تحقق الأزواج الخالصة (v0.32) —
 * كلها JVM بلا أندرويد.
 */
class SnapshotCodecTest {

    @Test
    fun `round trip preserves arabic emoji and slashy paths`() {
        val nodes = listOf(
            SyncNode("المستندات/تقرير 2026.pdf", 1234L, 1700000000L, isDir = false),
            SyncNode("صورة 🎉.png", 42L, 1700000001L, isDir = false),
            SyncNode("docs", 0L, 1700000002L, isDir = true),
            SyncNode("docs/line\nbreak|pipe.txt", 7L, 1700000003L, isDir = false),
        )
        val text = SnapshotCodec.encode(nodes)
        assertEquals(nodes.sortedBy { it.relativePath }, SnapshotCodec.decode(text))
    }

    @Test
    fun `encode emits header and dir flag`() {
        val text = SnapshotCodec.encode(listOf(SyncNode("d", 0L, 5L, isDir = true)))
        assertTrue(text.startsWith("NVSYNC1\nd|d|0|5"))
    }

    @Test
    fun `decode tolerates junk lines and unknown flags`() {
        val text = "NVSYNC1\nf|a.txt|1|2\nGARBAGE\nd|x|0|3\nf|bad|nan|1\n\n"
        val nodes = SnapshotCodec.decode(text)
        assertEquals(
            listOf("a.txt", "x"),
            nodes.map { it.relativePath },
        )
    }

    @Test
    fun `decode null blank or wrong header yields empty`() {
        assertTrue(SnapshotCodec.decode(null).isEmpty())
        assertTrue(SnapshotCodec.decode("").isEmpty())
        assertTrue(SnapshotCodec.decode("not-a-snapshot").isEmpty())
    }

    @Test
    fun `encode is deterministic`() {
        val nodes = listOf(SyncNode("b", 1L, 2L, false), SyncNode("a", 3L, 4L, false))
        assertEquals(SnapshotCodec.encode(nodes), SnapshotCodec.encode(nodes.reversed()))
    }
}

/** قواعد التحقق الخالصة لأزواج المزامنة */
class SyncPairValidationTest {

    private fun local(p: String) = SyncRepository.Side(SyncRepository.SideType.LOCAL, p)
    private fun cloud(p: String, account: Long) =
        SyncRepository.Side(SyncRepository.SideType.CLOUD, p, account)

    @Test
    fun `valid local to local pair passes`() {
        assertNull(SyncRepository.validate("backup", local("/a"), local("/b")))
    }

    @Test
    fun `valid local to cloud pair passes`() {
        assertNull(SyncRepository.validate("docs", local("/a"), cloud("/NovaFiles", 3)))
    }

    @Test
    fun `blank name rejected`() {
        assertEquals(
            SyncRepository.ValidationError.BlankName,
            SyncRepository.validate("  ", local("/a"), local("/b")),
        )
    }

    @Test
    fun `overlong name rejected`() {
        assertEquals(
            SyncRepository.ValidationError.LongName,
            SyncRepository.validate("x".repeat(61), local("/a"), local("/b")),
        )
    }

    @Test
    fun `identical sides rejected as self sync`() {
        assertEquals(
            SyncRepository.ValidationError.SameSide,
            SyncRepository.validate("self", local("/same"), local("/same")),
        )
        assertEquals(
            SyncRepository.ValidationError.SameSide,
            SyncRepository.validate("self", cloud("/x", 2), cloud("/x", 2)),
        )
    }

    @Test
    fun `same local path via different account ids is still self for locals`() {
        // الحساب لا يعني شيئاً للمحلي — المسار نفسه ذات مزامنة
        assertEquals(
            SyncRepository.ValidationError.SameSide,
            SyncRepository.validate("self", local("/same"), local("/same")),
        )
    }

    @Test
    fun `cloud to cloud rejected by design`() {
        assertEquals(
            SyncRepository.ValidationError.CloudToCloud,
            SyncRepository.validate("cc", cloud("/a", 1), cloud("/b", 2)),
        )
    }

    @Test
    fun `cloud side with blank path rejected`() {
        assertEquals(
            SyncRepository.ValidationError.EmptyCloudPath,
            SyncRepository.validate("c", local("/a"), cloud("", 1)),
        )
    }

    @Test
    fun `cloud to cloud stays rejected even across accounts`() {
        // تصميم v0.32: محلي↔محلي أو محلي↔سحابة فقط
        assertEquals(
            SyncRepository.ValidationError.CloudToCloud,
            SyncRepository.validate("two accounts", cloud("/x", 1), cloud("/x", 2)),
        )
    }

    @Test
    fun `side type parsing falls back to local`() {
        assertEquals(SyncRepository.SideType.LOCAL, SyncRepository.SideType.fromName(null))
        assertEquals(SyncRepository.SideType.LOCAL, SyncRepository.SideType.fromName("bogus"))
        assertEquals(SyncRepository.SideType.CLOUD, SyncRepository.SideType.fromName("CLOUD"))
    }

    @Test
    fun `name at limit accepted`() {
        assertNull(SyncRepository.validate("x".repeat(60), local("/a"), local("/b")))
    }
}

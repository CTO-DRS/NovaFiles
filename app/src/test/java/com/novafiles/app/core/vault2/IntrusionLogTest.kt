package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** سجل التسلل: دوام مشفر، صور، سقف، تفاهة آمنة */
class IntrusionLogTest {

    private fun newLog(): Pair<InMemoryFs, IntrusionLog> {
        val fs = InMemoryFs()
        return fs to IntrusionLog(fs, ByteArray(32) { 6 })
    }

    @Test
    fun record_andReload() {
        val (fs, log) = newLog()
        log.record(IntrusionLog.Kind.WRONG_PIN, "محاولة أولى")
        log.record(IntrusionLog.Kind.WRONG_BIOMETRIC, "بصمة غير مطابقة")
        log.close()

        val reloaded = IntrusionLog(fs, ByteArray(32) { 6 })
        assertEquals(2, reloaded.size())
        assertEquals(IntrusionLog.Kind.WRONG_BIOMETRIC, reloaded.all()[0].kind) // الأحدث أولاً
        assertEquals("محاولة أولى", reloaded.all()[1].detail)
    }

    @Test
    fun record_withPhoto_encryptedRoundTrip() {
        val (fs, log) = newLog()
        val photoBytes = ByteArray(500) { (it * 7).toByte() }
        val entry = log.record(IntrusionLog.Kind.WRONG_PIN, "مع صورة", ByteArrayInputStream(photoBytes))
        assertTrue(entry.photoRef != null)
        assertTrue(fs.files.containsKey("photos/${entry.photoRef}.b"))

        val out = ByteArrayOutputStream()
        assertTrue(log.openPhoto(entry, out))
        assertTrue(photoBytes.contentEquals(out.toByteArray()))
    }

    @Test
    fun openPhoto_missingOrNoRef() {
        val (_, log) = newLog()
        val noPhoto = log.record(IntrusionLog.Kind.WRONG_PIN)
        assertFalse(log.openPhoto(noPhoto, ByteArrayOutputStream()))
        assertNull(noPhoto.photoRef)
    }

    @Test
    fun capAt200Entries() {
        val (_, log) = newLog()
        repeat(210) { log.record(IntrusionLog.Kind.WRONG_PIN, "#$it") }
        assertEquals(200, log.size())
        assertEquals("#209", log.all().first().detail) // الأحدث محفوظ
    }

    @Test
    fun clear_removesPhotosAndEntries() {
        val (fs, log) = newLog()
        val e = log.record(IntrusionLog.Kind.WRONG_PIN, "صورة", ByteArrayInputStream(ByteArray(10)))
        log.clear()
        assertEquals(0, log.size())
        assertFalse(fs.files.containsKey("photos/${e.photoRef}.b"))
    }

    @Test
    fun corruptedLog_startsEmpty_neverBlocksVault() {
        val fs = InMemoryFs()
        fs.files[IntrusionLog.LOG_PATH] = "بيانات فاسدة تماماً".toByteArray()
        val log = IntrusionLog(fs, ByteArray(32) { 6 })
        assertEquals(0, log.size())
        // ويستطيع التسجيل من جديد
        log.record(IntrusionLog.Kind.WRONG_PIN)
        assertEquals(1, log.size())
    }

    @Test
    fun differentMaster_cannotReadLog() {
        val fs = InMemoryFs()
        IntrusionLog(fs, ByteArray(32) { 6 }).record(IntrusionLog.Kind.WRONG_PIN, "سري")
        val hostile = IntrusionLog(fs, ByteArray(32) { 7 })
        assertEquals(0, hostile.size()) // سجل فاسد بمفتاح غريب → يبدأ فارغاً
    }
}

package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مخزن شاشة الطرفية — خالصة على JVM */
class TerminalBufferTest {

    // ═══ الإلحاق الأساسي ═══

    @Test
    fun appendSingleLine() {
        val buf = TerminalBuffer()
        buf.append(ShellLine.ok("hello"))
        assertEquals(1, buf.size)
        assertEquals("hello", buf.snapshot()[0].text)
    }

    @Test
    fun appendAllPreservesOrder() {
        val buf = TerminalBuffer()
        buf.appendAll(listOf(ShellLine.ok("a"), ShellLine.err("b"), ShellLine.dir("c")))
        assertEquals(listOf("a", "b", "c"), buf.snapshot().map { it.text })
        assertEquals(ShellColor.RED, buf.snapshot()[1].color)
    }

    @Test
    fun appendAllEmptyDoesNotBumpVersion() {
        val buf = TerminalBuffer()
        val v = buf.version
        buf.appendAll(emptyList())
        assertEquals(v, buf.version)
    }

    // ═══ سعة الحلقة ═══

    @Test
    fun ringDropsOldestBeyondCapacity() {
        val buf = TerminalBuffer(capacity = 3)
        for (i in 1..5) buf.append(ShellLine.ok("line$i"))
        assertEquals(3, buf.size)
        assertEquals(listOf("line3", "line4", "line5"), buf.snapshot().map { it.text })
    }

    // ═══ النسخة والمسح ═══

    @Test
    fun versionBumpsOnChange() {
        val buf = TerminalBuffer()
        val v0 = buf.version
        buf.append(ShellLine.ok("x"))
        assertEquals(v0 + 1, buf.version)
        buf.clear()
        assertEquals(v0 + 2, buf.version)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun clearOnEmptyKeepsVersion() {
        val buf = TerminalBuffer()
        val v = buf.version
        buf.clear()
        assertEquals(v, buf.version)
    }

    // ═══ applyOutcome ═══

    @Test
    fun applyOutcomeAppendsLines() {
        val buf = TerminalBuffer()
        buf.applyOutcome(ShellOutcome(listOf(ShellLine.ok("out1"), ShellLine.ok("out2"))))
        assertEquals(2, buf.size)
    }

    @Test
    fun applyOutcomeWithClearResetsFirst() {
        val buf = TerminalBuffer()
        buf.append(ShellLine.ok("old"))
        buf.applyOutcome(
            ShellOutcome(listOf(ShellLine.ok("fresh")), clearRequested = true),
        )
        assertEquals(listOf("fresh"), buf.snapshot().map { it.text })
    }

    // ═══ النص الخام للقارئ ═══

    @Test
    fun plainTextJoinsWithNewlines() {
        val buf = TerminalBuffer()
        buf.appendAll(listOf(ShellLine.ok("one"), ShellLine.ok("two")))
        assertEquals("one\ntwo", buf.plainText())
    }

    @Test
    fun plainTextEmptyIsEmpty() {
        assertEquals("", TerminalBuffer().plainText())
    }
}

package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * v0.30 — إضافات الطرفية: صيغ إضافية، صيغ أعلام لاصقة، مستويات وسط، ورفض غير المعروف.
 */
class NovaShellV30ExtrasTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var shell: NovaShell

    @org.junit.Before
    fun setUp() {
        home = tmp.newFolder("storage")
        shell = NovaShell(home)
    }

    private fun run(cmd: String) = shell.execute(cmd)
    private fun f(rel: String) = File(home, rel)

// ═══ v0.30 إضافات ═══

@Test
fun zipCreatesTarXzByExtensionAndUnzipExtractsIt() {
    val src = File(home, "tx").apply { mkdirs() }
    File(src, "n.txt").writeText("xz text")
    assertTrue(run("zip c.tar.xz tx/").ok)
    assertTrue(f("c.tar.xz").exists())
    val r = run("unzip c.tar.xz tx-out")
    assertTrue("extract: ${r.lines.map { it.text }}", r.ok)
    assertEquals("xz text", f("tx-out/tx/n.txt").readText())
}

@Test
fun zipSplitGluedEqualsFormWorks() {
    val src = File(home, "sp2").apply { mkdirs() }
    val rnd = java.util.Random(7L)
    File(src, "b.bin").writeBytes(ByteArray(9 * 1024).also { rnd.nextBytes(it) })
    assertTrue(run("zip glued.zip --split=4K sp2/").ok)
    assertTrue(f("glued.zip.nvasplit").exists())
    assertTrue((1..99).count { f("glued.zip.%03d".format(it)).exists() } >= 2)
}

@Test
fun zipMidLevelDigitAccepted() {
    val src = File(home, "mid").apply { mkdirs() }
    File(src, "m.txt").writeText("mid level")
    assertTrue(run("zip m.zip -3 mid/").ok)
    assertTrue(f("m.zip").exists())
}

@Test
fun zipRejectsNameWithoutKnownExtension() {
    val src = File(home, "noext").apply { mkdirs() }
    File(src, "f.txt").writeText("f")
    val r = run("zip archive noext/")
    assertFalse(r.ok)
    assertTrue(r.lines.any { it.text.contains("unsupported archive type") })
}
}

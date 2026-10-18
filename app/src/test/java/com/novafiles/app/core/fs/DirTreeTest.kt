package com.novafiles.app.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات شجرة المجلدات الجانبية — بناء خالص على مجلدات حقيقية مؤقتة.
 */
class DirTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(path: String): File {
        val f = File(tmp.root, path)
        f.mkdirs()
        return f
    }

    // ═══ التوسعة الافتراضية ═══

    @Test
    fun defaultExpandedContainsAllAncestorsAndCurrent() {
        val current = "/storage/emulated/0/DCIM/Camera"
        val expanded = DirTree.defaultExpanded(current)
        assertTrue(expanded.contains("/storage/emulated/0"))
        assertTrue(expanded.contains("/storage/emulated/0/DCIM"))
        assertTrue(expanded.contains("/storage/emulated/0/DCIM/Camera"))
        assertEquals(3, expanded.size)
    }

    @Test
    fun defaultExpandedOfStorageRootIsItself() {
        val expanded = DirTree.defaultExpanded("/storage/emulated/0")
        assertEquals(setOf("/storage/emulated/0"), expanded)
    }

    @Test
    fun defaultExpandedIgnoresSystemPseudoRoots() {
        // /storage نفسها لا تدخل القائمة — ليست جذر عرض
        val expanded = DirTree.defaultExpanded("/storage/emulated/0")
        assertFalse(expanded.contains("/storage"))
        assertFalse(expanded.contains("/storage/emulated"))
    }

    // ═══ البناء ═══

    @Test
    fun buildRendersExpandedChainWithDepths() {
        val storage = dir("storage0")
        val dcim = dir("storage0/DCIM")
        val camera = dir("storage0/DCIM/Camera")
        dir("storage0/Download")
        val current = camera.absolutePath
        val expanded = DirTree.defaultExpanded(current)

        val nodes = DirTree.build(listOf(storage), current, expanded)

        assertEquals(listOf(storage.name, "DCIM", "Camera", "Download"), nodes.map { it.name })
        assertEquals(listOf(0, 1, 2, 1), nodes.map { it.depth })
        // التمييز: المسار الحالي وأسلافه
        assertTrue(nodes[0].isAncestor && nodes[1].isAncestor)
        assertTrue(nodes[2].isCurrent)
        assertFalse(nodes[3].isCurrent)
        assertFalse(nodes[3].isAncestor)
        // الحالة المفتوحة
        assertTrue(nodes[0].isExpanded && nodes[1].isExpanded)
        assertFalse(nodes[3].isExpanded)
        // العقدة الحالية بلا أبناء ظاهرين
        assertFalse(nodes[2].hasVisibleChildren)
        assertTrue(nodes[0].hasVisibleChildren)
    }

    @Test
    fun buildCollapsedRootShowsSingleRow() {
        val storage = dir("s")
        dir("s/Alpha")
        dir("s/Beta")
        // توسعة فارغة = كل شيء مطوي حتى الجذر — صف واحد
        val nodes = DirTree.build(listOf(storage), storage.absolutePath, expanded = emptySet())
        assertEquals(1, nodes.size)
        assertFalse(nodes[0].isExpanded)
        assertFalse(nodes[0].hasVisibleChildren)
    }

    @Test
    fun buildListsOnlyDirectoriesSortedCaseInsensitive() {
        val storage = dir("root")
        File(storage, "ملف.txt").writeText("not a folder")
        dir("root/zeta")
        dir("root/Alpha")
        dir("root/beta")

        val nodes = DirTree.build(
            listOf(storage),
            storage.absolutePath,
            expanded = setOf(storage.absolutePath),
        )
        // الجذر + المجلدات فقط مرتبة أبجدياً بلا حساسية حالة
        assertEquals(listOf("root", "Alpha", "beta", "zeta"), nodes.map { it.name })
        assertTrue(nodes.none { it.name == "ملف.txt" })
    }

    @Test
    fun buildTogglesExpansionOnAndOff() {
        val storage = dir("t")
        dir("t/Sub")
        val sub = File(storage, "Sub")

        val open = DirTree.build(listOf(storage), storage.absolutePath, setOf(storage.absolutePath, sub.absolutePath))
        assertEquals(listOf("t", "Sub"), open.map { it.name })
        assertTrue(open[1].isExpanded && open[0].hasVisibleChildren)

        val closed = DirTree.build(listOf(storage), storage.absolutePath, emptySet())
        assertEquals(listOf("t"), closed.map { it.name })
    }

    @Test
    fun buildMultipleRootsRenderAtDepthZero() {
        val internal = dir("internal")
        val sd = dir("sdcard")
        val nodes = DirTree.build(listOf(internal, sd), internal.absolutePath, emptySet())
        assertEquals(listOf(internal.name, sd.name), nodes.map { it.name })
        assertEquals(listOf(0, 0), nodes.map { it.depth })
    }

    @Test
    fun customListDirsProviderIsRespected() {
        val storage = dir("p")
        val nodes = DirTree.build(
            roots = listOf(storage),
            current = storage.absolutePath,
            expanded = setOf(storage.absolutePath),
            listDirs = { listOf(File(it, "Virtual")) },
        )
        assertEquals(listOf("p", "Virtual"), nodes.map { it.name })
    }
}

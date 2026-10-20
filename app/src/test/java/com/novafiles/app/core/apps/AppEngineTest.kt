package com.novafiles.app.core.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * اختبارات AppEngine الخالصة — v0.22
 * تغطية: الفرز الثلاثي + كسر التعادل، التصفية، السلسلة، الحجم الكلي،
 * تعقيم التسمية الاحتياطية (محارف غير قانونية/فارغ/طويل/عربي)،
 * مسح APK (عمق/مخفي/حالة الأحرف/حد/ترتيب)، هدف النسخ الاحتياطي واصطداماته.
 */
class AppEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun app(
        pkg: String,
        label: String = pkg,
        ver: String = "1.0",
        code: Long = 1,
        size: Long = 10L,
        installed: Long = 1000L,
        system: Boolean = false,
    ) = AppEntry(pkg, label, ver, code, size, installed, system, "/data/app/$pkg/base.apk")

    // ─────────────────────── الفرز ───────────────────────

    @Test
    fun `sort by name is case-insensitive then package tiebreak`() {
        val apps = listOf(
            app("com.b", "beta"),
            app("com.a2", "Alpha"),
            app("com.a1", "alpha"),
        )
        val sorted = AppEngine.sort(apps, AppSort.NAME)
        assertEquals(listOf("com.a1", "com.a2", "com.b"), sorted.map { it.packageName })
    }

    @Test
    fun `sort by size descending with package tiebreak`() {
        val apps = listOf(
            app("com.small", size = 5),
            app("com.big2", size = 100),
            app("com.big1", size = 100),
            app("com.mid", size = 50),
        )
        val sorted = AppEngine.sort(apps, AppSort.SIZE)
        assertEquals(
            listOf("com.big1", "com.big2", "com.mid", "com.small"),
            sorted.map { it.packageName },
        )
    }

    @Test
    fun `sort by recent installs descending with tiebreak`() {
        val apps = listOf(
            app("com.old", installed = 100),
            app("com.new2", installed = 900),
            app("com.new1", installed = 900),
            app("com.mid", installed = 500),
        )
        val sorted = AppEngine.sort(apps, AppSort.RECENT)
        assertEquals(
            listOf("com.new1", "com.new2", "com.mid", "com.old"),
            sorted.map { it.packageName },
        )
    }

    @Test
    fun `sort is deterministic across repeated calls`() {
        val apps = listOf(app("com.c"), app("com.a"), app("com.b"))
        val first = AppEngine.sort(apps, AppSort.NAME)
        repeat(5) { assertEquals(first, AppEngine.sort(apps, AppSort.NAME)) }
    }

    // ─────────────────────── التصفية ───────────────────────

    @Test
    fun `filter user excludes system apps`() {
        val apps = listOf(app("com.user1"), app("com.sys", system = true), app("com.user2"))
        val filtered = AppEngine.filter(apps, AppFilter.USER)
        assertEquals(listOf("com.user1", "com.user2"), filtered.map { it.packageName })
    }

    @Test
    fun `filter system keeps only system apps`() {
        val apps = listOf(app("com.user1"), app("com.sys", system = true), app("com.sys2", system = true))
        val filtered = AppEngine.filter(apps, AppFilter.SYSTEM)
        assertEquals(listOf("com.sys", "com.sys2"), filtered.map { it.packageName })
    }

    @Test
    fun `filter ALL keeps everything`() {
        val apps = listOf(app("com.a"), app("com.s", system = true))
        assertEquals(2, AppEngine.filter(apps, AppFilter.ALL).size)
    }

    @Test
    fun `present chains filter then sort`() {
        val apps = listOf(
            app("com.z.user", label = "zeta", size = 1),
            app("com.a.sys", label = "alpha", system = true),
            app("com.m.user", label = "mike", size = 9),
        )
        val result = AppEngine.present(apps, AppFilter.USER, AppSort.NAME)
        assertEquals(listOf("com.m.user", "com.z.user"), result.map { it.packageName })
    }

    // ─────────────────────── الحجم الكلي ───────────────────────

    @Test
    fun `user total bytes ignores system apps`() {
        val apps = listOf(
            app("com.u1", size = 100),
            app("com.s", size = 999, system = true),
            app("com.u2", size = 23),
        )
        assertEquals(123L, AppEngine.userTotalBytes(apps))
    }

    @Test
    fun `user total bytes empty list is zero`() {
        assertEquals(0L, AppEngine.userTotalBytes(emptyList()))
    }

    // ─────────────────── تسمية النسخة الاحتياطية ───────────────────

    @Test
    fun `backup name simple label with version`() {
        assertEquals("VLC_v3.5.4(204).apk", AppEngine.backupFileName("VLC", "3.5.4", 204))
    }

    @Test
    fun `backup name sanitizes illegal filesystem characters`() {
        val name = AppEngine.backupFileName("my:app/is*bad?", "1.0", 7)
        assertFalse(name.contains(':'))
        assertFalse(name.contains('/'))
        assertFalse(name.contains('*'))
        assertFalse(name.contains('?'))
        assertTrue(name.endsWith("_v1.0(7).apk"))
    }

    @Test
    fun `backup name folds repeated spaces and trims`() {
        assertEquals("My App_v1(1).apk", AppEngine.backupFileName("  My   App  ", "1", 1))
    }

    @Test
    fun `backup name empty or control-only falls back to app`() {
        assertEquals("app_v1(1).apk", AppEngine.backupFileName("", "1", 1))
        assertEquals("app_v1(1).apk", AppEngine.backupFileName("///", "1", 1))
        assertEquals("app_v2(2).apk", AppEngine.backupFileName("\u0000\u0001", "2", 2))
    }

    @Test
    fun `backup name keeps arabic label intact`() {
        val name = AppEngine.backupFileName("آلة حاسبة", "1.2", 3)
        assertTrue(name.startsWith("آلة حاسبة_v1.2(3).apk"))
    }

    @Test
    fun `backup name truncates long labels to sixty chars`() {
        val long = "A".repeat(200)
        val name = AppEngine.backupFileName(long, "1", 1)
        val stem = name.removeSuffix("_v1(1).apk")
        assertTrue("stem len=${stem.length}", stem.length <= 60)
        assertNotEquals(long, stem)
    }

    @Test
    fun `backup name blank version becomes zero`() {
        assertEquals("X_v0(5).apk", AppEngine.backupFileName("X", "", 5))
    }

    // ─────────────────────── مسح ملفات APK ───────────────────────

    private fun file(dir: java.io.File, rel: String, size: Long): java.io.File {
        val f = java.io.File(dir, rel)
        f.parentFile.mkdirs()
        f.writeText("x".repeat(size.toInt()))
        return f
    }

    @Test
    fun `apk scan finds apk files case-insensitively ordered by size desc`() {
        val root = tmp.newFolder("scan1")
        val small = file(root, "small.apk", 10)
        val big = file(root, "BIG.APK", 100)
        val other = file(root, "note.txt", 500)
        val found = AppEngine.findApkFiles(listOf(root))
        assertEquals(listOf(big, small), found)
        assertFalse(found.contains(other))
    }

    @Test
    fun `apk scan respects depth limit`() {
        val root = tmp.newFolder("scan2")
        file(root, "a.apk", 5)
        val deep = file(root, "l1/l2/l3/l4/deep.apk", 50)
        val found = AppEngine.findApkFiles(listOf(root), maxDepth = 3)
        assertFalse(found.contains(deep))
        assertEquals(1, found.size)
    }

    @Test
    fun `apk scan skips hidden files and folders`() {
        val root = tmp.newFolder("scan3")
        file(root, ".hidden.apk", 5)
        file(root, ".hiddenfolder/inside.apk", 50)
        assertEquals(0, AppEngine.findApkFiles(listOf(root)).size)
    }

    @Test
    fun `apk scan enforces limit keeping largest`() {
        val root = tmp.newFolder("scan4")
        for (i in 1..10) file(root, "app$i.apk", i * 10L)
        val found = AppEngine.findApkFiles(listOf(root), limit = 3)
        assertEquals(3, found.size)
        assertTrue(found[0].name.endsWith("10.apk"))
    }

    @Test
    fun `apk scan deduplicates roots and ignores unreadable`() {
        val root = tmp.newFolder("scan5")
        val f = file(root, "one.apk", 10)
        val found = AppEngine.findApkFiles(listOf(root, root, tmp.root))
        assertEquals(listOf(f), found)
    }

    @Test
    fun `apk scan skips zero-length files`() {
        val root = tmp.newFolder("scan6")
        file(root, "empty.apk", 0)
        file(root, "real.apk", 10)
        val found = AppEngine.findApkFiles(listOf(root))
        assertEquals(1, found.size)
        assertEquals("real.apk", found[0].name)
    }

    // ─────────────────────── هدف النسخ الاحتياطي ───────────────────────

    @Test
    fun `backup dir derives subfolder under downloads`() {
        val dl = tmp.newFolder("dl")
        assertEquals(java.io.File(dl, "NovaFiles-APKs"), AppEngine.backupDir(dl))
    }

    @Test
    fun `backup target returns plain path when free`() {
        val dir = tmp.newFolder("bk")
        val t = AppEngine.backupTarget(dir, "App_v1(1).apk")
        assertEquals(java.io.File(dir, "App_v1(1).apk"), t)
    }

    @Test
    fun `backup target appends counter on collision`() {
        val dir = tmp.newFolder("bk2")
        java.io.File(dir, "App_v1(1).apk").writeText("x")
        assertEquals(java.io.File(dir, "App_v1(1)(2).apk").path, AppEngine.backupTarget(dir, "App_v1(1).apk").path)
        java.io.File(dir, "App_v1(1)(2).apk").writeText("x")
        assertEquals(java.io.File(dir, "App_v1(1)(3).apk").path, AppEngine.backupTarget(dir, "App_v1(1).apk").path)
    }
}

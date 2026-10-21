package com.novafiles.app.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * v0.30.0 — اختبارات مصنع الأرشيفات على أقراص مؤقتة 100%.
 * تغطي: كل الصيغ (ZIP/7z/TAR.GZ/BZ2/XZ/TAR)، المستويات، كلمات المرور،
 * البصمة التلقائية، الاستثناءات، التقدم، والإلغاء التعاوني.
 */
class ArchiveCreateEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    /** نص عربي + بيانات ثنائية قابلة للضغط — بنية مختبرة منذ v0.12 */
    private fun makeSampleTree(name: String): File {
        val src = tmp.newFolder(name)
        val text = "محتوى عربي متكرر للضغط ".repeat(400).toByteArray(Charsets.UTF_8)
        val bin = ByteArray(120 * 1024) { i -> (i % 7).toByte() } // شديد القابلية للضغط
        writeFile(src, "ملف عربي.txt", text)
        writeFile(src, "مجلد فرعي/داخلي/ثنائي.bin", bin)
        writeFile(src, "plain.txt", "hello world".toByteArray())
        return src
    }

    private val engine get() = ArchiveCreateEngine()

    private fun spec(
        sources: List<File>,
        out: File,
        format: CreateFormat,
        level: CreateLevel = CreateLevel.BALANCED,
        password: CharArray? = null,
        split: Long = 0,
    ) = CreateSpec(sources, out, format, level, password, split)

    // ═══ استنتاج الصيغة من الامتداد ═══

    @Test
    fun formatForRecognizesAllSupportedExtensions() {
        assertEquals(CreateFormat.ZIP, CreateFormat.forFileName("a.zip"))
        assertEquals(CreateFormat.SEVEN_Z, CreateFormat.forFileName("a.7z"))
        assertEquals(CreateFormat.TAR, CreateFormat.forFileName("a.tar"))
        assertEquals(CreateFormat.TAR_GZ, CreateFormat.forFileName("a.tar.gz"))
        assertEquals(CreateFormat.TAR_BZ2, CreateFormat.forFileName("a.tar.bz2"))
        assertEquals(CreateFormat.TAR_XZ, CreateFormat.forFileName("a.tar.xz"))
    }

    @Test
    fun formatForIsCaseInsensitive() {
        assertEquals(CreateFormat.SEVEN_Z, CreateFormat.forFileName("BACKUP.7Z"))
        assertEquals(CreateFormat.TAR_GZ, CreateFormat.forFileName("My-Archive.TAR.GZ"))
    }

    @Test
    fun formatForReturnsNullForUnknownOrAmbiguous() {
        assertNull(CreateFormat.forFileName("document.pdf"))
        assertNull(CreateFormat.forFileName("image.gz")) // ضغط أحادي — إنشاء غير مدعوم
        assertNull(CreateFormat.forFileName("archive"))
    }

    // ═══ TAR خام: جولة كاملة بأسماء عربية متداخلة ═══

    @Test
    fun tarRoundTripWithArabicNestedNames() {
        val src = makeSampleTree("tar-src")
        val out = File(tmp.root, "tape.tar")
        val result = engine.create(spec(listOf(src), out, CreateFormat.TAR))

        assertEquals(3, result.fileCount)
        assertEquals(3, CommonArchive.extractAll(out, tmp.newFolder("tar-out")))
        val dest = tmp.newFolder("tar-out2")
        CommonArchive.extractAll(out, dest)
        assertTrue(File(dest, "tar-src/ملف عربي.txt").length() > 0)
        assertEquals("hello world", File(dest, "tar-src/plain.txt").readText())
    }

    @Test
    fun tarPreservesFileSizesAndDirectoryEntries() {
        val src = makeSampleTree("tar-sizes")
        val out = File(tmp.root, "sizes.tar")
        engine.create(spec(listOf(src), out, CreateFormat.TAR))

        val entries = CommonArchive.list(out)
        assertTrue(entries.any { it.isDirectory && it.name.startsWith("tar-sizes/") })
        assertEquals(120 * 1024L, entries.first { it.name == "tar-sizes/مجلد فرعي/داخلي/ثنائي.bin" }.size)
    }

    // ═══ TAR.GZ / BZ2 / XZ: جولات كاملة ═══

    @Test
    fun tarGzRoundTripAndContentIntegrity() {
        val src = makeSampleTree("gz-src")
        val out = File(tmp.root, "t.tar.gz")
        val result = engine.create(spec(listOf(src), out, CreateFormat.TAR_GZ))
        assertTrue("tar.gz يجب أن يكون أصغر من مدخلاته", result.outputSize < result.inputBytes)

        val dest = tmp.newFolder("gz-out")
        assertEquals(3, CommonArchive.extractAll(out, dest))
        val bin = File(dest, "gz-src/مجلد فرعي/داخلي/ثنائي.bin")
        assertEquals(120 * 1024L, bin.length())
    }

    @Test
    fun tarBz2RoundTripAndContentIntegrity() {
        val src = makeSampleTree("bz2-src")
        val out = File(tmp.root, "t.tar.bz2")
        engine.create(spec(listOf(src), out, CreateFormat.TAR_BZ2))

        val dest = tmp.newFolder("bz2-out")
        assertEquals(3, CommonArchive.extractAll(out, dest))
        assertTrue(File(dest, "bz2-src/ملف عربي.txt").length() > 0)
    }

    @Test
    fun tarXzRoundTripAndContentIntegrity() {
        val src = makeSampleTree("xz-src")
        val out = File(tmp.root, "t.tar.xz")
        engine.create(spec(listOf(src), out, CreateFormat.TAR_XZ))
        assertTrue(out.length() > 0)

        val dest = tmp.newFolder("xz-out")
        assertEquals(3, CommonArchive.extractAll(out, dest))
        assertEquals("hello world", File(dest, "xz-src/plain.txt").readText())
    }

    // ═══ 7z: LZMA2 وSTORE وكلمات المرور ═══

    @Test
    fun sevenZipRoundTripWithLzma2() {
        val src = makeSampleTree("7z-src")
        val out = File(tmp.root, "c.7z")
        val result = engine.create(spec(listOf(src), out, CreateFormat.SEVEN_Z))
        assertEquals(3, result.fileCount)

        val dest = tmp.newFolder("7z-out")
        assertEquals(3, CommonArchive.extractAll(out, dest))
        assertEquals(120 * 1024L, File(dest, "7z-src/مجلد فرعي/داخلي/ثنائي.bin").length())
    }

    @Test
    fun sevenZipStoreMethodProducesBiggerOrEqualArchiveThanLzma2() {
        val src = makeSampleTree("7z-store")
        val stored = File(tmp.root, "s.7z")
        val lzma = File(tmp.root, "l.7z")
        engine.create(spec(listOf(src), stored, CreateFormat.SEVEN_Z, CreateLevel.STORE))
        engine.create(spec(listOf(src), lzma, CreateFormat.SEVEN_Z, CreateLevel.MAXIMUM))
        assertTrue("STORE ≥ LZMA2 في الحجم", stored.length() >= lzma.length())
    }

    @Test
    fun sevenZipPasswordRoundTripWithCorrectPassword() {
        val src = makeSampleTree("7z-pass")
        val out = File(tmp.root, "p.7z")
        engine.create(spec(listOf(src), out, CreateFormat.SEVEN_Z, password = "سرّ-123".toCharArray()))

        SevenZFile(out, "سرّ-123".toCharArray()).use { sf ->
            var files = 0
            var e = sf.nextEntry
            while (e != null) {
                if (!e.isDirectory) files++
                e = sf.nextEntry
            }
            assertEquals(3, files)
        }
    }

    @Test
    fun sevenZipWrongPasswordFailsOnRead() {
        val src = makeSampleTree("7z-wrong")
        val out = File(tmp.root, "w.7z")
        engine.create(spec(listOf(src), out, CreateFormat.SEVEN_Z, password = "correct".toCharArray()))
        try {
            SevenZFile(out, "wrong".toCharArray()).use { sf ->
                var e = sf.nextEntry
                while (e != null) { sf.getInputStream(e).read(); e = sf.nextEntry }
            }
            fail("كان يجب أن يفشل فك التشفير بكلمة مرور خاطئة")
        } catch (_: Exception) {
            // متوقع — AES يرفض المفتاح الخاطئ
        }
    }

    // ═══ ZIP المشفّر AES-256 عبر Zip4j ═══

    @Test
    fun encryptedZipRoundTripWithCorrectPassword() {
        val src = makeSampleTree("ez-src")
        val out = File(tmp.root, "sec.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP, password = "كلمة-المرور".toCharArray()))

        val dest = tmp.newFolder("ez-out")
        net.lingala.zip4j.ZipFile(out, "كلمة-المرور".toCharArray()).extractAll(dest.absolutePath)
        assertEquals(120 * 1024L, File(dest, "ez-src/مجلد فرعي/داخلي/ثنائي.bin").length())
        assertEquals("hello world", File(dest, "ez-src/plain.txt").readText())
    }

    @Test
    fun encryptedZipRejectsWrongPassword() {
        val src = makeSampleTree("ez-wrong")
        val out = File(tmp.root, "wr.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP, password = "right".toCharArray()))
        try {
            net.lingala.zip4j.ZipFile(out, "wrong".toCharArray()).extractAll(tmp.newFolder("wr-out").absolutePath)
            fail("كلمة المرور الخاطئة يجب أن تُرفض")
        } catch (_: Exception) {
            // متوقع
        }
    }

    @Test
    fun encryptedZipCarriesAesHeaderAndRoundTripsViaZip4j() {
        // تحقق توافق: المدخلات مشفرة AES فعلاً وتُقرأ بكلمة المرور الصحيحة
        val src = makeSampleTree("ez-cross")
        val out = File(tmp.root, "cross.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP, password = "shared".toCharArray()))

        val zf = net.lingala.zip4j.ZipFile(out, "shared".toCharArray())
        val header = zf.fileHeaders.first { it.fileName == "ez-cross/plain.txt" }
        assertTrue("المدخل يجب أن يكون مشفراً", header.isEncrypted)
        assertEquals(
            net.lingala.zip4j.model.enums.EncryptionMethod.AES,
            header.encryptionMethod,
        )
        val dest = tmp.newFolder("cross-out")
        zf.extractAll(dest.absolutePath)
        assertEquals("hello world", File(dest, "ez-cross/plain.txt").readText())
    }

    @Test
    fun plainZipStaysUnencryptedAndReadableByJavaZip() {
        val src = makeSampleTree("pz-src")
        val out = File(tmp.root, "plain.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP))

        ZipFile(out, ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            val names = zf.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toSet()
            assertEquals(setOf("pz-src/ملف عربي.txt", "pz-src/مجلد فرعي/داخلي/ثنائي.bin", "pz-src/plain.txt"), names)
        }
    }

    // ═══ مستويات الضغط ═══

    @Test
    fun zipStoreLevelProducesBiggerArchiveThanMaximum() {
        val src = makeSampleTree("lv-zip")
        val stored = File(tmp.root, "lv0.zip")
        val maxed = File(tmp.root, "lv9.zip")
        engine.create(spec(listOf(src), stored, CreateFormat.ZIP, CreateLevel.STORE))
        engine.create(spec(listOf(src), maxed, CreateFormat.ZIP, CreateLevel.MAXIMUM))
        assertTrue("STORE(${stored.length()}) يجب أن يفوق MAXIMUM(${maxed.length()})", stored.length() > maxed.length())
    }

    @Test
    fun gzipLevelAffectsOutputSize() {
        val src = makeSampleTree("lv-gz")
        val fastest = File(tmp.root, "f.tar.gz")
        val maxed = File(tmp.root, "m.tar.gz")
        engine.create(spec(listOf(src), fastest, CreateFormat.TAR_GZ, CreateLevel.FASTEST))
        engine.create(spec(listOf(src), maxed, CreateFormat.TAR_GZ, CreateLevel.MAXIMUM))
        assertTrue("أسرع GZ ≥ أقصى GZ", fastest.length() >= maxed.length())
    }

    @Test
    fun allLevelsProduceValidArchivesForEveryFormat() {
        val src = makeSampleTree("lv-all")
        val formats = listOf(
            CreateFormat.ZIP, CreateFormat.SEVEN_Z, CreateFormat.TAR,
            CreateFormat.TAR_GZ, CreateFormat.TAR_BZ2, CreateFormat.TAR_XZ,
        )
        var n = 0
        for (format in formats) {
            for (level in CreateLevel.entries) {
                val out = File(tmp.root, "lv-$n.${format.ext.removePrefix(".")}")
                val r = engine.create(spec(listOf(src), out, format, level))
                assertTrue("الناتج يجب أن يكون موجوداً: ${out.name}", out.exists() && r.outputSize > 0)
                n++
            }
        }
        assertEquals(24, n)
    }

    // ═══ البصمة التلقائية SHA-256 ═══

    @Test
    fun resultSha256MatchesIndependentDigest() {
        val src = makeSampleTree("sha-src")
        val out = File(tmp.root, "s.zip")
        val result = engine.create(spec(listOf(src), out, CreateFormat.ZIP))

        val md = MessageDigest.getInstance("SHA-256")
        out.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val expected = md.digest().joinToString("") { "%02x".format(it) }
        assertEquals(expected, result.sha256)
        assertEquals(64, result.sha256.length)
    }

    // ═══ الاستثناءات الذكية ═══

    @Test
    fun outputInsideSourceFolderIsExcludedFromArchive() {
        val src = tmp.newFolder("self-src")
        writeFile(src, "keep.txt", "data".toByteArray())
        val out = File(src, "inside.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP))

        ZipFile(out, ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertFalse("الأرشيف يجب ألا يضم نفسه", names.any { it.contains("inside.zip") })
            assertTrue(names.contains("self-src/keep.txt"))
        }
    }

    @Test
    fun oldSplitArtifactsAreExcludedOnRecreate() {
        val src = tmp.newFolder("old-src")
        writeFile(src, "data.txt", "x".repeat(5000).toByteArray())
        // بقايا تقسيم قديمة تحمل اسم الأرشيف الجديد
        writeFile(src, "old.zip.001", ByteArray(100))
        writeFile(src, "old.zip.002", ByteArray(100))
        writeFile(src, "old.zip.nvasplit", "junk".toByteArray())

        val out = File(src, "old.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP))
        ZipFile(out, ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertFalse(names.any { it.endsWith(".001") || it.endsWith(".002") || it.endsWith(".nvasplit") })
        }
    }

    // ═══ التقدم والإلغاء والأخطاء ═══

    @Test
    fun progressIsMonotonicAndReachesTotal() {
        val src = makeSampleTree("prog-src")
        val out = File(tmp.root, "p.tar.gz")
        val seen = ArrayList<Pair<Long, Long>>()
        val result = engine.create(spec(listOf(src), out, CreateFormat.TAR_GZ)) { d, t -> seen += d to t }

        assertTrue("يجب أن تُطلق نقاط تقدم", seen.isNotEmpty())
        for (i in 1 until seen.size) assertTrue(seen[i].first >= seen[i - 1].first)
        assertEquals(result.inputBytes, seen.last().first)
        assertEquals(result.inputBytes, seen.last().second)
    }

    @Test
    fun cancellationBetweenEntriesThrowsCooperatively() {
        val src = tmp.newFolder("cancel-src")
        writeFile(src, "a.txt", "aaaa".repeat(1000).toByteArray())
        writeFile(src, "b.txt", "bbbb".repeat(1000).toByteArray())
        val out = File(tmp.root, "c.zip")

        var calls = 0
        val engine2 = ArchiveCreateEngine(isCancelled = { calls++ >= 2 })
        try {
            engine2.create(spec(listOf(src), out, CreateFormat.ZIP))
            fail("كان يجب إلغاء الإنشاء")
        } catch (_: CreateCancelled) {
            // متوقع
        }
    }

    @Test
    fun emptySourcesRejected() {
        try {
            engine.create(spec(emptyList(), File(tmp.root, "n.zip"), CreateFormat.ZIP))
            fail("لا مصادر يجب أن يُرفض")
        } catch (_: IllegalArgumentException) {
            // متوقع
        }
    }

    @Test
    fun nonexistentSourcesAreFilteredThenRejectedIfEmpty() {
        val ghost = File(tmp.root, "ghost.bin")
        try {
            engine.create(spec(listOf(ghost), File(tmp.root, "g.zip"), CreateFormat.ZIP))
            fail("مصادر وهمية يجب أن تُرفض")
        } catch (_: IllegalArgumentException) {
            // متوقع
        }
    }

    @Test
    fun splitTooSmallRejected() {
        val src = tmp.newFolder("small-src")
        writeFile(src, "x.txt", "y".toByteArray())
        try {
            engine.create(spec(listOf(src), File(tmp.root, "sm.zip"), CreateFormat.ZIP, split = 512))
            fail("أقل من 1024 بايت يجب أن يُرفض")
        } catch (_: IllegalArgumentException) {
            // متوقع
        }
    }

    // ═══ الحصائل والإحصاءات ═══

    @Test
    fun resultCountsAreAccurate() {
        val src = makeSampleTree("cnt-src")
        writeFile(src, "مجلد فرعي/فارغ.txt", "z".toByteArray())
        val out = File(tmp.root, "c.tar")
        val result = engine.create(spec(listOf(src), out, CreateFormat.TAR))

        assertEquals("4 ملفات (لا مجلدات)", 4, result.fileCount)
        assertEquals(result.inputBytes, File(src, "ملف عربي.txt").length() +
            File(src, "مجلد فرعي/داخلي/ثنائي.bin").length() +
            File(src, "plain.txt").length() + File(src, "مجلد فرعي/فارغ.txt").length())
        assertEquals(out.length(), result.outputSize)
    }

    @Test
    fun multipleRootSourcesKeepTheirOwnNamespaces() {
        val a = tmp.newFolder("root-a")
        val b = tmp.newFolder("root-b")
        writeFile(a, "same-name.txt", "من A".toByteArray())
        writeFile(b, "same-name.txt", "من B".toByteArray())
        val out = File(tmp.root, "multi.zip")
        engine.create(spec(listOf(a, b), out, CreateFormat.ZIP))

        val dest = tmp.newFolder("multi-out")
        ZipArchive.extractAll(out, dest)
        assertEquals("من A", File(dest, "root-a/same-name.txt").readText())
        assertEquals("من B", File(dest, "root-b/same-name.txt").readText())
    }

    @Test
    fun emptyDirectoryBecomesExplicitArchiveEntry() {
        val src = tmp.newFolder("empty-dir-src")
        writeFile(src, "file.txt", "f".toByteArray())
        File(src, "مجلد فارغ").mkdirs()
        val out = File(tmp.root, "ed.zip")
        engine.create(spec(listOf(src), out, CreateFormat.ZIP))

        val names = ZipArchive.list(out).map { it.name }
        assertTrue(names.any { it == "empty-dir-src/مجلد فارغ/" })
    }

    @Test
    fun destinationParentsAreCreatedAutomatically() {
        val src = tmp.newFolder("parents-src")
        writeFile(src, "x.txt", "x".toByteArray())
        val out = File(tmp.root, "deep/er/est/out.7z")
        engine.create(spec(listOf(src), out, CreateFormat.SEVEN_Z))
        assertTrue(out.exists() && out.length() > 0)
    }

    @Test
    fun collectIsDeterministicAcrossCalls() {
        val src = tmp.newFolder("det-src")
        for (i in 20 downTo 1) writeFile(src, "f%02d.txt".format(i), "d$i".toByteArray())
        val first = engine.collect(listOf(src), File(tmp.root, "det.zip")).map { it.entryName }
        val second = engine.collect(listOf(src), File(tmp.root, "det.zip")).map { it.entryName }
        assertEquals(first, second)
        assertEquals(first.sorted(), first) // فرز أبجدي في كل مستوى
    }

// ═══════════ v0.30 — إضافات تغطية الحصائل والعقود ═══════════

@Test
fun createSpecEqualityUsesPasswordContent() {
    val a = CreateSpec(emptyList(), File("/tmp/a.zip"), CreateFormat.ZIP, password = "x".toCharArray())
    val b = CreateSpec(emptyList(), File("/tmp/a.zip"), CreateFormat.ZIP, password = "x".toCharArray())
    val c = CreateSpec(emptyList(), File("/tmp/a.zip"), CreateFormat.ZIP, password = "y".toCharArray())
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertTrue(a != c)
}

@Test
fun archiveRequestEqualityIgnoresPasswordIdentity() {
    val a = ArchiveRequest(CreateFormat.SEVEN_Z, password = "pw".toCharArray())
    val b = ArchiveRequest(CreateFormat.SEVEN_Z, password = "pw".toCharArray())
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertTrue(a != a.copy(splitBytes = 1024L))
}

@Test
fun levelsMapToExpectedCodecSettings() {
    assertEquals(0, CreateLevel.STORE.zipLevel)
    assertEquals(9, CreateLevel.MAXIMUM.zipLevel)
    assertEquals(1, CreateLevel.FASTEST.lzmaPreset)
    assertEquals(9, CreateLevel.MAXIMUM.gzipLevel)
    assertEquals(9, CreateLevel.MAXIMUM.bzipBlock)
    assertEquals(6, CreateLevel.BALANCED.lzmaPreset)
    assertEquals(6, CreateLevel.BALANCED.xzPreset)
}

@Test
fun sevenZipPasswordWithArabicNameRoundTrips() {
    val src = tmp.newFolder("7z-ar")
    writeFile(src, "ملف سري.txt", "محتوى مشفر".toByteArray())
    val out = File(tmp.root, "ar.7z")
    engine.create(spec(listOf(src), out, CreateFormat.SEVEN_Z, password = "مرور1".toCharArray()))

    SevenZFile(out, "مرور1".toCharArray()).use { sf ->
        // أول مدخل هو مجلد الجذر — نتخطاه إلى أول ملف
        var e = sf.nextEntry
        while (e != null && e.isDirectory) e = sf.nextEntry
        assertNotNull(e)
        assertEquals("7z-ar/ملف سري.txt", e!!.name)
    }
}

@Test
fun encryptedZipStoreLevelValid() {
    val src = tmp.newFolder("ez-store")
    val raw = ByteArray(2048) { (it % 13).toByte() }
    writeFile(src, "raw.bin", raw)
    val out = File(tmp.root, "st.zip")
    engine.create(spec(listOf(src), out, CreateFormat.ZIP, CreateLevel.STORE, password = "s".toCharArray()))

    val dest = tmp.newFolder("st-out")
    net.lingala.zip4j.ZipFile(out, "s".toCharArray()).extractAll(dest.absolutePath)
    assertTrue(raw.contentEquals(File(dest, "ez-store/raw.bin").readBytes()))
}

@Test
fun progressNeverExceedsTotal() {
    val src = makeSampleTree("prog-cap")
    val out = File(tmp.root, "pc.zip")
    var maxDone = 0L
    var total = -1L
    engine.create(spec(listOf(src), out, CreateFormat.ZIP)) { d, t ->
        maxDone = maxOf(maxDone, d)
        total = t
    }
    assertTrue("التقدم لا يتجاوز الإجمالي", maxDone <= total)
    assertTrue(total > 0)
}

@Test
fun encryptedZipProgressReachesTotal() {
    val src = makeSampleTree("ez-prog")
    val out = File(tmp.root, "ep.zip")
    val seen = ArrayList<Pair<Long, Long>>()
    engine.create(spec(listOf(src), out, CreateFormat.ZIP, password = "p".toCharArray())) { d, t -> seen += d to t }
    assertEquals(seen.last().first, seen.last().second)
}

@Test
fun tarPosixHandlesVeryLongArabicNames() {
    val src = tmp.newFolder("tar-long")
    val deep = "مجلد/".repeat(15) + "اسم ملف عربي طويل جداً ".repeat(4) + ".txt"
    writeFile(src, deep, "طويل".toByteArray())
    val out = File(tmp.root, "long.tar")
    engine.create(spec(listOf(src), out, CreateFormat.TAR))

    val dest = tmp.newFolder("tar-long-out")
    assertEquals(1, CommonArchive.extractAll(out, dest))
    assertTrue(File(dest, "tar-long/$deep").exists())
}
}

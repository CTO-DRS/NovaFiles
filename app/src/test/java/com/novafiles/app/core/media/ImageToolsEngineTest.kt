package com.novafiles.app.core.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات محرك أدوات الصور — الخطة، الاصطدام، الرياضيات، والتحذيرات */
class ImageToolsEngineTest {

    private val engine = ImageToolsEngine()

    private lateinit var dir: File
    private lateinit var out: File

    @org.junit.Before
    fun setup() {
        dir = createTempDir("imgsrc")
        out = createTempDir("imgout")
    }

    @org.junit.After
    fun teardown() {
        dir.deleteRecursively()
        out.deleteRecursively()
    }

    private fun img(name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }

    // ═══════════════ الصيغ ═══════════════

    @Test
    fun `format from name basic`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromName("a.jpg"))
        assertEquals(ImageFormat.PNG, ImageFormat.fromName("a.png"))
        assertEquals(ImageFormat.WEBP, ImageFormat.fromName("a.webp"))
    }

    @Test
    fun `format accepts jpeg alias`() {
        assertEquals(ImageFormat.JPEG, ImageFormat.fromName("photo.jpeg"))
    }

    @Test
    fun `format unknown or extensionless returns null`() {
        assertNull(ImageFormat.fromName("a.gif"))
        assertNull(ImageFormat.fromName("noext"))
    }

    @Test
    fun `format detection is case insensitive`() {
        assertEquals(ImageFormat.PNG, ImageFormat.fromName("PHOTO.PNG"))
    }

    @Test
    fun `format alpha and quality properties`() {
        assertFalse(ImageFormat.JPEG.supportsAlpha)
        assertTrue(ImageFormat.PNG.supportsAlpha)
        assertTrue(ImageFormat.WEBP.supportsAlpha)
        assertTrue(ImageFormat.JPEG.supportsQuality)
        assertFalse(ImageFormat.PNG.supportsQuality)
        assertTrue(ImageFormat.WEBP.supportsQuality)
    }

    // ═══════════════ رياضيات التصغير ═══════════════

    @Test
    fun `downscale preserves aspect ratio landscape`() {
        val (w, h) = engine.computeScaledDimensions(4000, 2000, 1920, 1080)
        assertEquals(1920, w)
        assertEquals(960, h)
    }

    @Test
    fun `downscale preserves aspect ratio portrait`() {
        val (w, h) = engine.computeScaledDimensions(2000, 4000, 1080, 1920)
        assertEquals(960, w)
        assertEquals(1920, h)
    }

    @Test
    fun `downscale limited by height when width fits`() {
        val (w, h) = engine.computeScaledDimensions(1000, 4000, 1920, 1080)
        assertEquals(270, w)
        assertEquals(1080, h)
    }

    @Test
    fun `no upscale when source fits box`() {
        val (w, h) = engine.computeScaledDimensions(800, 600, 1920, 1080)
        assertEquals(800, w)
        assertEquals(600, h)
    }

    @Test
    fun `downscale never returns zero dimensions`() {
        // صورة طويلة جداً — الأرضية 1 بكسل
        val (w, h) = engine.computeScaledDimensions(100000, 100, 50, 50)
        assertEquals(50, w)
        assertEquals(1, h)
    }

    @Test
    fun `invalid dimensions pass through without scaling`() {
        assertEquals(0 to 0, engine.computeScaledDimensions(0, 0, 100, 100))
        assertEquals(-5 to 300, engine.computeScaledDimensions(-5, 300, 100, 100))
    }

    // ═══════════════ الجودة ═══════════════

    @Test
    fun `quality clamped to range`() {
        assertEquals(1, engine.clampQuality(-10))
        assertEquals(100, engine.clampQuality(250))
        assertEquals(75, engine.clampQuality(75))
    }

    // ═══════════════ اسم الناتج ═══════════════

    @Test
    fun `output name swaps extension on convert`() {
        assertEquals("photo.jpg", engine.resolveOutputName("photo.png", ImageFormat.JPEG))
        assertEquals("photo.webp", engine.resolveOutputName("photo.jpg", ImageFormat.WEBP))
    }

    @Test
    fun `output name keeps extension without convert`() {
        assertEquals("photo.png", engine.resolveOutputName("photo.png", null))
        assertEquals("photo.png", engine.resolveOutputName("photo.png", ImageFormat.PNG))
    }

    @Test
    fun `output name handles jpeg alias`() {
        // مصدر jpeg → صيغة محددة: الامتداد يتوحّد على ext الرسمي
        assertEquals("a.jpg", engine.resolveOutputName("a.jpeg", ImageFormat.JPEG))
    }

    @Test
    fun `output name with no extension falls back untouched`() {
        assertEquals("noext", engine.resolveOutputName("noext", null))
    }

    // ═══════════════ الاصطدام ═══════════════

    @Test
    fun `collision free target passes through`() {
        val target = File(out, "a.png")
        val r = engine.resolveCollision(target, ImageCollisionPolicy.RENAME)
        assertEquals(target, r.file)
        assertFalse(r.renamed)
        assertFalse(r.overwrites)
    }

    @Test
    fun `collision rename finds first free slot`() {
        val target = File(out, "a.png")
        target.writeText("x")
        val r1 = engine.resolveCollision(target, ImageCollisionPolicy.RENAME)
        assertEquals("a (1).png", r1.file.name)
        r1.file.writeText("x")
        val r2 = engine.resolveCollision(target, ImageCollisionPolicy.RENAME)
        assertEquals("a (2).png", r2.file.name)
    }

    @Test
    fun `collision skip flags collision without rename`() {
        val target = File(out, "a.png")
        target.writeText("x")
        val r = engine.resolveCollision(target, ImageCollisionPolicy.SKIP)
        assertTrue(r.collides)
        assertFalse(r.renamed)
    }

    @Test
    fun `collision overwrite keeps target and flags overwrites`() {
        val target = File(out, "a.png")
        target.writeText("x")
        val r = engine.resolveCollision(target, ImageCollisionPolicy.OVERWRITE)
        assertEquals(target, r.file)
        assertTrue(r.overwrites)
        assertFalse(r.renamed)
    }

    // ═══════════════ بناء الخطة ═══════════════

    @Test
    fun `plan skips unsupported formats`() {
        img("note.txt")
        val plan = engine.buildItem(File(dir, "note.txt"), emptyList(), out, ImageCollisionPolicy.RENAME)
        assertTrue(plan.skipped)
        assertEquals(ImageSkipReason.UNSUPPORTED_FORMAT, plan.skipReason)
    }

    @Test
    fun `plan skips gif even though bitmap decodes it`() {
        // إعادة ترميز GIF غير مدعومة عبر Bitmap.compress — خارج المدعوم
        img("anim.gif")
        val plan = engine.buildItem(File(dir, "anim.gif"), emptyList(), out, ImageCollisionPolicy.RENAME)
        assertTrue(plan.skipped)
        assertEquals(ImageSkipReason.UNSUPPORTED_FORMAT, plan.skipReason)
    }

    @Test
    fun `plan skips missing files`() {
        val plan = engine.buildItem(File(dir, "ghost.jpg"), emptyList(), out, ImageCollisionPolicy.RENAME)
        assertTrue(plan.skipped)
        assertEquals(ImageSkipReason.UNREADABLE, plan.skipReason)
    }

    @Test
    fun `plan convert changes extension and warns alpha loss png to jpeg`() {
        img("shot.png")
        val plan = engine.buildItem(
            File(dir, "shot.png"),
            listOf(ImageOp.Convert(ImageFormat.JPEG)),
            out,
            ImageCollisionPolicy.RENAME,
        )
        assertFalse(plan.skipped)
        assertEquals("shot.jpg", plan.output.name)
        assertTrue(ImageWarning.ALPHA_LOSS in plan.warnings)
    }

    @Test
    fun `plan webp to jpeg warns alpha loss but webp target does not`() {
        img("a.webp")
        val toJpeg = engine.buildItem(
            File(dir, "a.webp"),
            listOf(ImageOp.Convert(ImageFormat.JPEG)),
            out, ImageCollisionPolicy.RENAME,
        )
        assertTrue(ImageWarning.ALPHA_LOSS in toJpeg.warnings)

        val toWebp = engine.buildItem(
            File(dir, "a.webp"),
            listOf(ImageOp.Convert(ImageFormat.WEBP)),
            out, ImageCollisionPolicy.RENAME,
        )
        assertFalse(ImageWarning.ALPHA_LOSS in toWebp.warnings)
    }

    @Test
    fun `plan compress to png warns quality ignored`() {
        img("a.png")
        val plan = engine.buildItem(
            File(dir, "a.png"),
            listOf(ImageOp.Compress(60)),
            out, ImageCollisionPolicy.RENAME,
        )
        assertTrue(ImageWarning.QUALITY_IGNORED in plan.warnings)
    }

    @Test
    fun `plan compress to jpeg has no quality warning`() {
        img("a.jpg")
        val plan = engine.buildItem(
            File(dir, "a.jpg"),
            listOf(ImageOp.Compress(60)),
            out, ImageCollisionPolicy.RENAME,
        )
        assertFalse(ImageWarning.QUALITY_IGNORED in plan.warnings)
    }

    @Test
    fun `plan downscale computes expected dims from known source dimensions`() {
        img("big.jpg")
        val plan = engine.buildItem(
            File(dir, "big.jpg"),
            listOf(ImageOp.Downscale(1920, 1080)),
            out, ImageCollisionPolicy.RENAME,
            sourceDimensions = mapOf(File(dir, "big.jpg").absolutePath to Pair(4000, 2000)),
        )
        assertEquals(1920, plan.expectedWidth)
        assertEquals(960, plan.expectedHeight)
    }

    @Test
    fun `plan downscale warns upscale noop for small source`() {
        img("small.jpg")
        val plan = engine.buildItem(
            File(dir, "small.jpg"),
            listOf(ImageOp.Downscale(1920, 1080)),
            out, ImageCollisionPolicy.RENAME,
            sourceDimensions = mapOf(File(dir, "small.jpg").absolutePath to Pair(800, 600)),
        )
        assertTrue(ImageWarning.UPSCALE_NOOP in plan.warnings)
        assertEquals(800, plan.expectedWidth)
    }

    @Test
    fun `plan identity in place no ops warns identity noop`() {
        img("same.jpg")
        val plan = engine.buildItem(File(dir, "same.jpg"), emptyList(), dir, ImageCollisionPolicy.RENAME)
        // نفس المجلد + بلا عمليات = خطة بلا أثر
        assertTrue(ImageWarning.IDENTITY_NOOP in plan.warnings)
        assertEquals(File(dir, "same.jpg").absolutePath, plan.output.absolutePath)
    }

    @Test
    fun `plan in place convert same format is identity`() {
        img("same.png")
        val plan = engine.buildItem(
            File(dir, "same.png"),
            listOf(ImageOp.Convert(ImageFormat.PNG)),
            dir, ImageCollisionPolicy.RENAME,
        )
        assertTrue(ImageWarning.IDENTITY_NOOP in plan.warnings)
    }

    @Test
    fun `plan quality compress in place same format is not identity`() {
        img("same.jpg")
        val plan = engine.buildItem(
            File(dir, "same.jpg"),
            listOf(ImageOp.Compress(50)),
            dir, ImageCollisionPolicy.RENAME,
        )
        // ضغط فعّال على JPEG — الخطة تكتب فوق المصدر عمداً (ليست هوية)
        assertFalse(ImageWarning.IDENTITY_NOOP in plan.warnings)
        assertFalse(plan.skipped)
    }

    @Test
    fun `plan collision rename against existing output in same dir`() {
        img("p.jpg")
        File(dir, "p.jpg").writeText("source")
        // نخرج في نفس المجلد بصيغة مختلفة مع وجود هدف بنفس الاسم مسبقاً
        File(dir, "p.png").writeText("existing target")
        val plan = engine.buildItem(
            File(dir, "p.jpg"),
            listOf(ImageOp.Convert(ImageFormat.PNG)),
            dir, ImageCollisionPolicy.RENAME,
        )
        assertEquals("p (1).png", plan.output.name)
        assertFalse(plan.skipped)
    }

    @Test
    fun `plan collision skip skips when output exists`() {
        img("p.jpg")
        File(dir, "p.png").writeText("existing")
        val plan = engine.buildItem(
            File(dir, "p.jpg"),
            listOf(ImageOp.Convert(ImageFormat.PNG)),
            dir, ImageCollisionPolicy.SKIP,
        )
        assertTrue(plan.skipped)
        assertEquals(ImageSkipReason.SAME_AS_SOURCE, plan.skipReason)
    }

    @Test
    fun `plan collision overwrite keeps existing target name`() {
        img("p.jpg")
        File(dir, "p.png").writeText("existing")
        val plan = engine.buildItem(
            File(dir, "p.jpg"),
            listOf(ImageOp.Convert(ImageFormat.PNG)),
            dir, ImageCollisionPolicy.OVERWRITE,
        )
        assertEquals("p.png", plan.output.name)
        assertFalse(plan.skipped)
        assertTrue(plan.overwrites)
    }

    @Test
    fun `plan last convert wins`() {
        img("a.png")
        val plan = engine.buildItem(
            File(dir, "a.png"),
            listOf(
                ImageOp.Convert(ImageFormat.JPEG),
                ImageOp.Convert(ImageFormat.WEBP),
            ),
            out, ImageCollisionPolicy.RENAME,
        )
        assertEquals("a.webp", plan.output.name)
    }

    @Test
    fun `plan last downscale wins`() {
        img("a.jpg")
        val plan = engine.buildItem(
            File(dir, "a.jpg"),
            listOf(
                ImageOp.Downscale(1920, 1080),
                ImageOp.Downscale(800, 800),
            ),
            out, ImageCollisionPolicy.RENAME,
            sourceDimensions = mapOf(File(dir, "a.jpg").absolutePath to Pair(4000, 2000)),
        )
        // 4000×2000 في صندوق 800×800 → 800×400
        assertEquals(800, plan.expectedWidth)
        assertEquals(400, plan.expectedHeight)
    }

    @Test
    fun `planBatch iterates all files with progress and deterministic order`() = runBlocking {
        img("b.jpg"); img("a.png"); img("c.webp")
        val files = listOf(File(dir, "b.jpg"), File(dir, "a.png"), File(dir, "c.webp"))
        val progress = ArrayList<Int>()
        val plan = engine.planBatch(files, emptyList(), out) { done, total -> progress.add(done * 100 + total) }
        assertEquals(3, plan.total)
        assertEquals(3, plan.executable)
        assertEquals(0, plan.skipped)
        // الترتيب حتمي = ترتيب المدخلات
        assertEquals("b.jpg", plan.items[0].output.name)
        assertEquals("a.png", plan.items[1].output.name)
        assertEquals("c.webp", plan.items[2].output.name)
        assertEquals(listOf(103, 203, 303), progress)
    }

    @Test
    fun `plan summary counters`() = runBlocking {
        img("a.jpg"); img("b.txt"); img("c.jpg")
        File(out, "c.jpg").writeText("existing") // c سيُعاد تسميته
        val files = listOf(File(dir, "a.jpg"), File(dir, "b.txt"), File(dir, "c.jpg"))
        val plan = engine.planBatch(
            files,
            listOf(ImageOp.Compress(80)),
            out,
            ImageCollisionPolicy.RENAME,
        )
        assertEquals(3, plan.total)
        assertEquals(2, plan.executable)
        assertEquals(1, plan.skipped)
        assertEquals(0, plan.warnings)
        assertEquals(0, plan.overwrites)
    }
}

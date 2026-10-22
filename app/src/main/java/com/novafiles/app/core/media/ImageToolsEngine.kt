package com.novafiles.app.core.media

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * v0.31.0 — محرك أدوات الصور الخالص (pure JVM)
 *
 * قاعدة الخارطة: خطة دفعة قابلة للاختبار 100% فوق java.io —
 * تحويل JPEG/PNG/WebP، ضغط بجودة هدف، تصغير بأبعاد قصوى مع حفظ النسبة،
 * ومعالجة اصطدام الأسماء بسياسات صريحة. التنفيذ الفعلي عبر Bitmap
 * يبقى في طبقة أندرويد (ui/media/ImageBatchExecutor) ويقود نفسه من هذه الخطة.
 */

/** صيغ الإخراج المدعومة لإعادة الترميز */
enum class ImageFormat(
    val ext: String,
    val mime: String,
    /** هل تحافظ الصيغة على قناة الشفافية (ألفا)؟ */
    val supportsAlpha: Boolean,
    /** هل لقيمة الجودة معنى في هذه الصيغة؟ (PNG بلا خسارة — الجودة بلا أثر) */
    val supportsQuality: Boolean,
) {
    JPEG("jpg", "image/jpeg", supportsAlpha = false, supportsQuality = true),
    PNG("png", "image/png", supportsAlpha = true, supportsQuality = false),
    WEBP("webp", "image/webp", supportsAlpha = true, supportsQuality = true),
    ;

    companion object {
        private val byExt = entries.associateBy { it.ext }
        private val aliases = mapOf("jpeg" to JPEG)

        /** استنتاج الصيغة من امتداد الاسم (يقبل jpeg كمرادف jpg) */
        fun fromName(name: String): ImageFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return byExt[ext] ?: aliases[ext]
        }
    }
}

/** التحذيرات الممكنة على عنصر من الخطة */
enum class ImageWarning {
    /** الصيغة الهدف لا تحفظ الشفافية (→ JPEG من PNG/WebP) */
    ALPHA_LOSS,

    /** الجودة بلا أثر على PNG (بلا خسارة) */
    QUALITY_IGNORED,

    /** المصدر أصغر أصلاً من الحد الأقصى — لن يُصغَّر */
    UPSCALE_NOOP,

    /** لا تغيير فعلي في الخطة: نفس الصيغة وبلا ضغط فعال أو تصغير */
    IDENTITY_NOOP,
}

/** سبب تخطي عنصر */
enum class ImageSkipReason {
    /** الامتداد غير مدعوم للقراءة/إعادة الترميز */
    UNSUPPORTED_FORMAT,

    /** الملف غير موجود أو غير قابل للقراءة */
    UNREADABLE,

    /** الناتج سيطابق المصدر والسياسة SKIP */
    SAME_AS_SOURCE,
}

/** سياسة اصطدام الاسم عند وجود الملف الهدف مسبقاً */
enum class ImageCollisionPolicy {
    /** أضف « (1)» و« (2)»… قبل الامتداد */
    RENAME,

    /** تخطَّ العنصر كلياً */
    SKIP,

    /** اكتب فوق الهدف */
    OVERWRITE,
}

/**
 * عملية واحدة على صورة. تُطبَّق بالترتيب؛ آخر Convert يحدد الصيغة النهائية
 * وآخر Downscale يحدد أبعاد الهدف.
 */
sealed class ImageOp {
    /** تحويل إلى صيغة أخرى */
    data class Convert(val format: ImageFormat) : ImageOp()

    /** ضغط بجودة هدف (1..100 — يُثبَّت تلقائياً) */
    data class Compress(val quality: Int) : ImageOp() {
        companion object {
            const val MIN = 1
            const val MAX = 100
        }
    }

    /** تصغير ليقطع في صندوق أبعاد قصوى مع حفظ النسبة (لا تكبير أبداً) */
    data class Downscale(val maxWidth: Int, val maxHeight: Int) : ImageOp() {
        init {
            require(maxWidth > 0 && maxHeight > 0) { "maxWidth/maxHeight يجب أن يكونا موجبين" }
        }
    }
}

/** عنصر جاهز من خطة الدفعة */
data class ImageBatchItem(
    val source: File,
    /** ملف الناتج النهائي (بعد حل الاصطدام وتغيير الامتداد) */
    val output: File,
    val ops: List<ImageOp>,
    /** الأبعاد المتوقعة بعد التصغير — null إن لم يوجد تصغير أو غابت أبعاد المصدر */
    val expectedWidth: Int? = null,
    val expectedHeight: Int? = null,
    val warnings: List<ImageWarning> = emptyList(),
    /** عنصر متخطى — لا يُنفَّذ */
    val skipped: Boolean = false,
    val skipReason: ImageSkipReason? = null,
    /** هل سيُستبدل ملف موجود؟ (سياسة OVERWRITE) */
    val overwrites: Boolean = false,
)

/** ملخص خطة الدفعة */
data class ImageBatchPlan(
    val items: List<ImageBatchItem>,
) {
    val total: Int get() = items.size
    val executable: Int get() = items.count { !it.skipped }
    val skipped: Int get() = items.count { it.skipped }
    val warnings: Int get() = items.sumOf { it.warnings.size }
    val overwrites: Int get() = items.count { it.overwrites }
}

/**
 * محرك أدوات الصور — يبني خطة دفعة حتمية قابلة للفحص قبل التنفيذ.
 * كل الدوال خالصة فوق java.io (فحص وجود الملفات فقط) — قابلة للاختبار
 * على أقراص مؤقتة 100%.
 */
class ImageToolsEngine {

    // ═══════════════ الدوال الرياضية الخالصة ═══════════════

    /**
     * أبعاد الهدف لقطع (w×h) داخل صندوق (maxW×maxH) مع حفظ النسبة.
     * لا تكبير أبداً: المصدر الأصغر من الصندوق يبقى كما هو.
     * الأبعاد غير الصالحة (0 أو سالب) تُمرَّر كما هي (لا-تصغير).
     */
    fun computeScaledDimensions(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || maxWidth <= 0 || maxHeight <= 0) return width to height
        if (width <= maxWidth && height <= maxHeight) return width to height
        val ratio = minOf(
            maxWidth.toDouble() / width,
            maxHeight.toDouble() / height,
        )
        val w = kotlin.math.floor(width * ratio).toInt().coerceAtLeast(1)
        val h = kotlin.math.floor(height * ratio).toInt().coerceAtLeast(1)
        return w to h
    }

    /** تثبيت الجودة ضمن 1..100 */
    fun clampQuality(quality: Int): Int = quality.coerceIn(ImageOp.Compress.MIN, ImageOp.Compress.MAX)

    /** هل الامتداد مدعوم للقراءة وإعادة الترميز؟ (jpg/jpeg/png/webp/bmp) */
    fun isConvertible(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_INPUT_EXTS
    }

    /** اسم الناتج بعد تغيير الامتداد للصيغة الهدف (أو الاسم نفسه بلا تحويل) */
    fun resolveOutputName(sourceName: String, targetFormat: ImageFormat?): String {
        val fmt = targetFormat ?: ImageFormat.fromName(sourceName) ?: return sourceName
        val base = sourceName.substringBeforeLast('.', sourceName)
        return "$base.${fmt.ext}"
    }

    /** حل اصطدام الاسم: يبحث عن «name (N).ext» الحر بدءاً من 1 */
    fun resolveCollision(target: File, policy: ImageCollisionPolicy): CollisionResult {
        if (!target.exists()) return CollisionResult(file = target, overwrites = false, renamed = false)
        if (policy == ImageCollisionPolicy.OVERWRITE) {
            return CollisionResult(file = target, overwrites = true, renamed = false)
        }
        if (policy == ImageCollisionPolicy.SKIP) {
            return CollisionResult(file = target, overwrites = false, renamed = false, collides = true)
        }
        // RENAME — «base (N).ext»
        val base = target.nameWithoutExtension
        val ext = target.name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val candidate = File(target.parentFile, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            if (!candidate.exists()) {
                return CollisionResult(file = candidate, overwrites = false, renamed = true)
            }
            n++
        }
    }

    // ═══════════════ بناء الخطة ═══════════════

    /**
     * بناء خطة دفعة حتمية لقائمة ملفات.
     *
     * @param ops العمليات المطلوبة (تُطبَّق على كل الملفات بنفس الترتيب)
     * @param outputDir مجلد الناتج (قد يكون نفس مجلد المصدر)
     * @param collisionPolicy سياسة اصطدام الاسم
     * @param sourceDimensions أبعاد المصادر المعروفة مسبقاً (مفاتيحها المسارات المطلقة)؛
     *   من يفتقدها تبقى أبعاده المتوقعة null ويصغّره التنفيذ فعلياً.
     * @param onProgress يُنادى بعد كل عنصر: (المعالَجة، الإجمالي)
     *
     * الإلغاء تعاوني عبر ensureActive على سياق الكوروتين الحالي.
     */
    suspend fun planBatch(
        files: List<File>,
        ops: List<ImageOp>,
        outputDir: File,
        collisionPolicy: ImageCollisionPolicy = ImageCollisionPolicy.RENAME,
        sourceDimensions: Map<String, Pair<Int, Int>> = emptyMap(),
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ImageBatchPlan {
        val items = ArrayList<ImageBatchItem>(files.size)
        var done = 0
        for (source in files) {
            currentCoroutineContext().ensureActive()
            items.add(buildItem(source, ops, outputDir, collisionPolicy, sourceDimensions))
            done++
            onProgress(done, files.size)
        }
        return ImageBatchPlan(items)
    }

    /** نسخة الخالصة المتزامنة لعنصر واحد — تُستخدم من planBatch ومن الاختبارات مباشرة */
    fun buildItem(
        source: File,
        ops: List<ImageOp>,
        outputDir: File,
        collisionPolicy: ImageCollisionPolicy,
        sourceDimensions: Map<String, Pair<Int, Int>> = emptyMap(),
    ): ImageBatchItem {
        // 1) الصلاحية
        if (!isConvertible(source.name)) {
            return ImageBatchItem(
                source = source, output = File(outputDir, source.name), ops = ops,
                skipped = true, skipReason = ImageSkipReason.UNSUPPORTED_FORMAT,
            )
        }
        if (!source.isFile || !source.canRead()) {
            return ImageBatchItem(
                source = source, output = File(outputDir, source.name), ops = ops,
                skipped = true, skipReason = ImageSkipReason.UNREADABLE,
            )
        }

        // 2) الصيغة النهائية: آخر Convert يفوز، وإلا صيغة المصدر
        val targetFormat = ops.filterIsInstance<ImageOp.Convert>().lastOrNull()?.format
            ?: ImageFormat.fromName(source.name)

        // 3) الأبعاد المتوقعة: أبعاد المصدر ثم آخر Downscale (مع تحذير لا-تكبير)
        val dims = sourceDimensions[source.absolutePath]
        var expectedW: Int? = null
        var expectedH: Int? = null
        val warnings = LinkedHashSet<ImageWarning>()

        val downscale = ops.filterIsInstance<ImageOp.Downscale>().lastOrNull()
        if (downscale != null && dims != null) {
            val (w, h) = computeScaledDimensions(dims.first, dims.second, downscale.maxWidth, downscale.maxHeight)
            expectedW = w
            expectedH = h
            if (w == dims.first && h == dims.second) warnings += ImageWarning.UPSCALE_NOOP
        }

        // 4) التحذيرات
        val sourceFormat = ImageFormat.fromName(source.name)
        if (targetFormat != null && !targetFormat.supportsAlpha && sourceFormat?.supportsAlpha == true) {
            warnings += ImageWarning.ALPHA_LOSS
        }
        val hasCompress = ops.any { it is ImageOp.Compress }
        if (hasCompress && targetFormat?.supportsQuality == false) {
            warnings += ImageWarning.QUALITY_IGNORED
        }

        // 5) اسم الناتج + الاصطدام
        val outName = resolveOutputName(source.name, targetFormat)
        var target = File(outputDir, outName)

        // خطة بلا أثر: نفس الملف، نفس الصيغة، بلا تصغير وبلا ضغط فعال
        val identity = target.absolutePath == source.absolutePath &&
            targetFormat == sourceFormat &&
            downscale == null &&
            (!hasCompress || targetFormat?.supportsQuality == false)
        if (identity) warnings += ImageWarning.IDENTITY_NOOP

        if (target.exists() && target.absolutePath != source.absolutePath) {
            val resolved = resolveCollision(target, collisionPolicy)
            if (collisionPolicy == ImageCollisionPolicy.SKIP && resolved.collides) {
                return ImageBatchItem(
                    source = source, output = target, ops = ops,
                    expectedWidth = expectedW, expectedHeight = expectedH,
                    warnings = warnings.toList(),
                    skipped = true, skipReason = ImageSkipReason.SAME_AS_SOURCE,
                )
            }
            target = resolved.file
        }

        return ImageBatchItem(
            source = source,
            output = target,
            ops = ops,
            expectedWidth = expectedW,
            expectedHeight = expectedH,
            warnings = warnings.toList(),
            overwrites = target.exists() && target.absolutePath != source.absolutePath,
        )
    }

    /** نتيجة حل الاصطدام */
    data class CollisionResult(
        val file: File,
        val overwrites: Boolean,
        val renamed: Boolean,
        val collides: Boolean = false,
    )

    companion object {
        /** المدخلات القابلة للفك وإعادة الترميز عبر Bitmap في أندرويد */
        val SUPPORTED_INPUT_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")
    }
}

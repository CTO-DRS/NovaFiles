package com.novafiles.app.core.media

/**
 * v0.31.0 — محرك فهرسة الوسائط الخالص (pure JVM)
 *
 * تكامل v0.31 مع الفهرس: تصنيف ملفات الوسائط القابلة للمعالجة،
 * عدّ المرشحين للتصغير (تمهيد لاقتراحات v0.34)، ومصفوفة دعم
 * الصيغ في مكان واحد تحتفل بها كل الشاشات.
 */

/** وصف وسائط خفيف — من الفهرس أو المتصفح بلا IO */
data class MediaFile(
    val name: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
)

/** إجراء متاح على ملف وسائط */
enum class MediaAction {
    /** تحويل صيغة (JPEG/PNG/WebP) */
    CONVERT,

    /** ضغط بجودة هدف */
    COMPRESS,

    /** تصغير بأبعاد قصوى */
    DOWNSCALE,

    /** تشغيل مدمج (صوت/فيديو) */
    PLAY,

    /** عرض PDF مدمج */
    VIEW_PDF,
}

/**
 * محرك الفهرسة — قرارات خالصة من (الاسم، الحجم، الأبعاد) فقط.
 * العتبات قابلة للحقن للاختبارات.
 */
class MediaCatalogEngine(
    /** عتبة الحجم لاعتبار الصورة مرشحة للتصغير (1MB افتراضياً) */
    private val sizeThresholdBytes: Long = 1L * 1024 * 1024,
    /** عتبة البعد الأكبر لاعتبار الصورة مرشحة للتصغير (2048px افتراضياً) */
    private val pixelThreshold: Int = 2048,
) {

    // ═══════════════ التصنيف ═══════════════

    /** هل الملف صورة قابلة للمعالجة (فك + إعادة ترميز عبر Bitmap)؟ */
    fun isConvertibleImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in ImageToolsEngine.SUPPORTED_INPUT_EXTS
    }

    /** هل الملف وسائط تشغيل (صوت/فيديو) حسب الامتداد؟ */
    fun isPlayable(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in PLAYABLE_EXTS
    }

    /** هل الملف PDF؟ */
    fun isPdf(name: String): Boolean = name.substringAfterLast('.', "").lowercase() == "pdf"

    /** الإجراءات المتاحة لملف — الترتيب ثابت: تشغيل/عرض ثم أدوات الصور */
    fun availableActions(name: String): List<MediaAction> {
        val actions = ArrayList<MediaAction>()
        if (isPlayable(name)) actions += MediaAction.PLAY
        if (isPdf(name)) actions += MediaAction.VIEW_PDF
        if (isConvertibleImage(name)) {
            actions += MediaAction.CONVERT
            actions += MediaAction.COMPRESS
            actions += MediaAction.DOWNSCALE
        }
        return actions
    }

    // ═══════════════ أهلية التصغير (تمهيد v0.34) ═══════════════

    /**
     * هل الصورة مرشحة للتصغير؟
     * تجاوز عتبة الحجم، أو تجاوز عتبة البعد الأكبر عند توفر الأبعاد.
     * الصور غير القابلة للمعالجة مستبعدة دائماً.
     */
    fun isDownscaleCandidate(file: MediaFile): Boolean {
        if (!isConvertibleImage(file.name)) return false
        if (file.sizeBytes > sizeThresholdBytes) return true
        val w = file.width ?: return false
        val h = file.height ?: return false
        return maxOf(w, h) > pixelThreshold
    }

    /** عدّ مرشحي التصغير في مجموعة ملفات */
    fun countDownscaleCandidates(files: List<MediaFile>): Int =
        files.count { isDownscaleCandidate(it) }

    /** مرشحو التصغير مرتبين هابطاً بالحجم (الأكبر أولاً — أثقلهم استحقاقاً) */
    fun downscaleCandidates(files: List<MediaFile>): List<MediaFile> =
        files.filter { isDownscaleCandidate(it) }.sortedByDescending { it.sizeBytes }

    // ═══════════════ مصفوفة الدعم ═══════════════

    /** صيغ الإخراج الممكنة لصورة مصدر — الكل متاح دائماً (الفك مضمون) */
    fun targetFormats(name: String): List<ImageFormat> =
        if (isConvertibleImage(name)) ImageFormat.entries.toList() else emptyList()

    /**
     * هل التحويل من صيغة إلى أخرى يفقد الشفافية؟
     * يعتمد على حفظ الألفا في الهدف (خصائص الصيغ نفسها).
     */
    fun alphaLossIfConverted(sourceFormat: ImageFormat, targetFormat: ImageFormat): Boolean =
        sourceFormat.supportsAlpha && !targetFormat.supportsAlpha

    // ═══════════════ عرض ═══════════════

    /** حجم مقروء B/KB/MB/GB — عشري واحد للـ KB وما فوق */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    companion object {
        /** امتدادا التشغيل — نفس عائلات FileType لكن محلية لهذا المحرك */
        val PLAYABLE_EXTS = setOf(
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma",
            "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts",
        )
    }
}

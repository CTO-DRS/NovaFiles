package com.novafiles.app.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.novafiles.app.core.media.ImageBatchItem
import com.novafiles.app.core.media.ImageFormat
import com.novafiles.app.core.media.ImageOp
import java.io.File

/**
 * v0.31.0 — منفذ خطة الصور عبر Bitmap (طبقة أندرويد).
 *
 * يستقبل عناصر الخطة الحتمية من ImageToolsEngine الخالص وينفذها:
 * فك بحدود مسبقة، تصغير خشن عبر inSampleSize ثم دقيق عبر
 * createScaledBitmap، وإعادة ترميز بالصيغة والجودة الهدفين.
 * كل القرارات (الاسم، الصيغة، الأبعاد، الاصطدام) جاءت من الخطة —
 * المنفذ لا يبتكر شيئاً.
 */
object ImageBatchExecutor {

    /** نتيجة تنفيذ الدفعة */
    data class Result(
        val done: Int,
        val failed: Int,
        val cancelled: Boolean,
    )

    /**
     * تنفيذ العناصر القابلة للتنفيذ فقط (المتخطى يُتجاهل).
     * @param onProgress يُنادى بعد كل عنصر: (المنتهية، الإجمالي القابل للتنفيذ، اسم العنصر الحالي)
     */
    suspend fun execute(
        items: List<ImageBatchItem>,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Result {
        val runnable = items.filter { !it.skipped }
        var done = 0
        var failed = 0
        val total = runnable.size
        for (item in runnable) {
            if (isCancelled()) return Result(done, failed, cancelled = true)
            val ok = runCatching { processOne(item) }.isSuccess
            if (ok) done++ else failed++
            onProgress(done + failed, total, item.source.name)
            kotlinx.coroutines.yield()
        }
        return Result(done, failed, cancelled = false)
    }

    /** معالجة عنصر واحد: فك → تصغير → ترميز */
    private fun processOne(item: ImageBatchItem) {
        val source = item.source
        val out = item.output

        // 1) قراءة الحدود الفعلية
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("decode failed: ${source.name}")
        }

        // 2) تصغير خشن عبر inSampleSize (قوى 2) لو طلب تصغير
        val downscale = item.ops.filterIsInstance<ImageOp.Downscale>().lastOrNull()
        val sampleOpts = BitmapFactory.Options().apply {
            inSampleSize = if (downscale != null) {
                roughSampleSize(bounds.outWidth, bounds.outHeight, downscale.maxWidth, downscale.maxHeight)
            } else 1
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath, sampleOpts)
            ?: throw IllegalStateException("decode failed: ${source.name}")

        // 3) تصغير دقيق يحفظ النسبة إلى الأبعاد المتوقعة (أو المحسوبة فعلياً)
        val scaled = if (downscale != null) {
            val (tw, th) = item.expectedWidth?.let { it to (item.expectedHeight ?: it) }
                ?: com.novafiles.app.core.media.ImageToolsEngine()
                    .computeScaledDimensions(decoded.width, decoded.height, downscale.maxWidth, downscale.maxHeight)
            if (tw > 0 && th > 0 && (decoded.width > tw || decoded.height > th)) {
                Bitmap.createScaledBitmap(decoded, tw, th, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else decoded
        } else decoded

        // 4) الصيغة النهائية والجودة — من الخطة حصراً
        val targetFormat = item.ops.filterIsInstance<ImageOp.Convert>().lastOrNull()?.format
            ?: ImageFormat.fromName(source.name)
            ?: ImageFormat.JPEG
        val quality = item.ops.filterIsInstance<ImageOp.Compress>()
            .firstOrNull()?.quality?.coerceIn(1, 100) ?: 100

        val compressFormat = when (targetFormat) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            // v0.41.1 — WEBP_LOSSY متاح من API 30؛ قبله يُستخدم WEBP الكلاسيكي (ضائع أيضاً)
            ImageFormat.WEBP ->
                if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val qualityForFormat = if (targetFormat.supportsQuality) quality else 100

        // 5) الكتابة — مع إزالة الهدف القديم عند الاستبدال (نفس المسار)
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        java.io.FileOutputStream(out).use { fos ->
            if (!scaled.compress(compressFormat, qualityForFormat, fos)) {
                throw IllegalStateException("encode failed: ${out.name}")
            }
            fos.flush()
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }

    /** أكبر قوة 2 تبقي الأبعاد فوق الهدف (تصغير خشن آمن) */
    internal fun roughSampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        var sample = 1
        if (w <= 0 || h <= 0) return sample
        while (w / (sample * 2) >= maxW / 2 || h / (sample * 2) >= maxH / 2) {
            sample *= 2
            if (sample >= 32) break
        }
        return sample
    }
}

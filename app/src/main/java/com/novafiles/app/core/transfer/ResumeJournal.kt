package com.novafiles.app.core.transfer

import java.io.File

/**
 * v0.13 — مجلة الاستئناف: سجل جانبي لملف .nfpart الجزئي.
 *
 * التنزيل المقاطَع يكتب في target.nfpart، والمجلة تحفظ بجانبه
 * (الحجم الكلي + ETag عن بُعد + المسار) حتى يعرف مدير النقل لاحقاً:
 * هل هذا الجزء ما زال صالحاً للاستئناف عبر Range أم يجب البدء من جديد؟
 *
 * التحقق ثلاثي: حجم مطابق + ETag مطابق (إن عرف الطرفان) + جزء أقصر من الكل.
 * الترميز: base64 للقيم النصية — صفر احتمال تعارض فواصل مع أسماء عربية.
 */
object ResumeJournal {

    data class State(
        val totalBytes: Long,
        val etag: String?,
        val remotePath: String,
    )

    private const val MAGIC = "NFPJ1"

    /** الملف الجزئي (بيانات التنزيل الناقصة) المصاحب لهدف التنزيل */
    fun partFile(target: File): File = File(target.parentFile, target.name + ".nfpart")

    /** سجل الحالة الجانبي — منفصل عن بيانات الجزء حتى لا يُفسده */
    fun journalFile(target: File): File = File(target.parentFile, target.name + ".nfjournal")

    fun save(target: File, state: State) {
        val etagB64 = java.util.Base64.getEncoder().encodeToString(
            (state.etag ?: "").toByteArray(Charsets.UTF_8),
        )
        val pathB64 = java.util.Base64.getEncoder().encodeToString(
            state.remotePath.toByteArray(Charsets.UTF_8),
        )
        journalFile(target).writeText("$MAGIC ${state.totalBytes} $etagB64 $pathB64")
    }

    fun load(target: File): State? = runCatching {
        val line = journalFile(target).readText().trim()
        val parts = line.split(' ')
        if (parts.size != 4 || parts[0] != MAGIC) return null
        val total = parts[1].toLongOrNull() ?: return null
        val etag = java.util.Base64.getDecoder().decode(parts[2]).toString(Charsets.UTF_8)
        val path = java.util.Base64.getDecoder().decode(parts[3]).toString(Charsets.UTF_8)
        State(total, etag.ifEmpty { null }, path)
    }.getOrNull()

    /** إزاحة الاستئناف الصالحة: 0 إن كان البدء من جديد */
    fun resumeOffset(target: File, expectedTotal: Long, expectedEtag: String?): Long {
        val part = partFile(target)
        if (!part.exists()) return 0L
        val st = load(target) ?: return 0L.also { part.delete(); journalFile(target).delete() }
        val consistent = st.totalBytes == expectedTotal &&
            (st.etag == null || expectedEtag == null || st.etag == expectedEtag) &&
            part.length() in 1 until expectedTotal
        return if (consistent) part.length()
        else 0L.also { part.delete(); journalFile(target).delete() }
    }

    fun clear(target: File) {
        partFile(target).delete()
        journalFile(target).delete()
    }
}

package com.novafiles.app.core.archive

import java.io.File

/**
 * حاجز الأمان المشترك لكل صيغ الأرشيفات (Zip-Slip / Tar-Slip / 7z-Slip):
 * يمنع مدخلات مثل ../evil.txt أو /etc/passwd أو C:\win من الكتابة خارج مجلد الوجهة.
 */
internal object ArchiveSafety {

    /** الوجهة الآمنة لمدخل داخل [destCanonical] حصراً — وإلا SecurityException */
    fun safeDest(destCanonical: File, entryName: String): File {
        // تطبيع الفواصل وتجريد بادئة المسار المطلق / محركات الأقراص
        var name = entryName.replace('\\', '/')
        while (name.startsWith("/")) name = name.substring(1)
        name = name.replace(Regex("^[A-Za-z]:"), "")
        // سلسلة canonical تكشف أي .. هاربة حتى لو تنكرت
        val candidate = File(destCanonical, name).canonicalFile
        val destPath = destCanonical.canonicalPath + File.separator
        if (candidate.path != destCanonical.path && !candidate.path.startsWith(destPath)) {
            throw SecurityException("archive_slip: $entryName")
        }
        return candidate
    }
}

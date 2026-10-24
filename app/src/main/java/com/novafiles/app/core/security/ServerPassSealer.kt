package com.novafiles.app.core.security

import javax.crypto.SecretKey

/**
 * v0.41.1 — ختم كلمات مرور الخوادم المدمجة (FTP/WebDAV) في DataStore.
 *
 * العيب المثبت بالتدقيق (P1): المفتاحان ftp_pass/dav_pass في ملف
 * nova_settings.preferences_pb كانا نصاً صريحاً على القرص — أي استخراج للملف
 * (ADB debug، نسخة احتياطية سابقة للقواعد الجديدة، جهاز مروّغ) يكشف كلمة المرور.
 * القواعد تستثني الملف الآن (إصلاح P0 في v0.41.0) لكن الدفاع في العمق يوجب
 * ألا يكون السر نصاً صريحاً أصلاً.
 *
 * الحل: صيغة الختم الموحدة NFCV1 نفسها المستخدمة في خزنة بيانات الاعتماد
 * (AesGcmVault/KeystoreVault) — المفتاح AES-256 يُولَّد داخل AndroidKeyStore
 * ولا يغادرها، والتشفير AES/GCM بـ IV عشوائي لكل عملية.
 *
 * سياسة الترحيل (قراءة قديمة→جديدة):
 *  1. الكتابة الجديدة تُختم دائماً في ftp_pass_enc/dav_pass_enc وتُبيد النص القديم فوراً.
 *  2. القراءة تفضّل المختوم؛ وإن غاب وعُثر على النص الموروث (نسخة محدثة فوقه لم تُشغّل
 *     الترحيل بعد) تُعاد قيمته مؤقتاً حتى ينجح الترحيل عند الإقلاع.
 *  3. الترحيل يعمل عند إقلاع التطبيق: ختم القيمة الموروثة ثم إبادة النص الصريح.
 *  4. فشل مغلق: بلا مفتاح لا تُكتب أسرار إطلاقاً — النص الموروث
 *     يبقى مكانه ويُعاد محاولة الترحيل في الإقلاع التالي.
 *
 * النواة خالصة JVM — كل القرارات هنا مختبرة بمفتاح عادي بلا أندرويد.
 */
object ServerPassSealer {

    /** بادئة الصيغة الموحدة — نفس سحر خزنة بيانات الاعتماد */
    const val MAGIC = "NFCV1"

    /** هل تبدأ القيمة بختم NFCV1؟ (null ليست مختومة) */
    fun isSealed(raw: String?): Boolean = raw != null && raw.startsWith("$MAGIC:")

    /** يختم نصاً صريحاً بصيغة NFCV1 — يرفض الفراغ (بلا مفتاح لا شيء يُختم) */
    fun seal(key: SecretKey, plain: String): String = AesGcmVault.sealTo(key, plain)

    /** يفك الختم — null عند أي عطب أو إن لم تكن القيمة مختومة أصلاً */
    fun unseal(key: SecretKey, raw: String): String? =
        if (isSealed(raw)) AesGcmVault.unsealFrom(key, raw) else null

    /**
     * القراءة الموحدة لسر خادم:
     *  - مختوم + مفتاح → قيمة مفكوكة، أو الافتراضي إن فسد التشفير (فشل مغلق للافتراضي).
     *  - مختوم بلا مفتاح (Keystore منهك) → الافتراضي — لا نعرض نصاً لا نملك مفتاحه.
     *  - لا مختوم → النص الموروث إن وجد (قبل اكتمال الترحيل)، وإلا الافتراضي.
     */
    fun decode(key: SecretKey?, sealed: String?, legacy: String?, fallback: String): String {
        if (sealed != null) {
            if (key == null) return fallback
            return unseal(key, sealed) ?: fallback
        }
        return legacy ?: fallback
    }
}

/**
 * خطة ترحيل قيمة واحدة (نص موروث → ختم) — نقية لتُختبر بلا DataStore.
 *
 * @property sealValue القيمة المختومة الجديدة المطلوب كتابتها (null = لا كتابة)
 * @property dropLegacy إبادة المفتاح النصي الموروث دائماً عند وجوده
 */
data class ServerPassMigration(val sealValue: String?, val dropLegacy: Boolean)

/**
 * يحسب خطة ترحيل قيمة سر خادم من حالة المخزن الخام:
 *  - لا نص موروث → لا شيء (المخزن سليم).
 *  - نص موروث فارغ → إبادة فقط (الفراغ ليس سراً — الافتراضي يحكم).
 *  - نص موروث بلا ختم → ختم القيمة + إبادة.
 *  - نص موروث مع ختم موجود → إبادة فقط (المختوم أصول).
 */
fun planServerPassMigration(key: SecretKey, legacy: String?, sealed: String?): ServerPassMigration? {
    legacy ?: return null
    if (legacy.isBlank()) return ServerPassMigration(sealValue = null, dropLegacy = true)
    if (sealed == null) return ServerPassMigration(sealValue = ServerPassSealer.seal(key, legacy), dropLegacy = true)
    return ServerPassMigration(sealValue = null, dropLegacy = true)
}

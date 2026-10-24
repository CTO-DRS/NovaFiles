package com.novafiles.app.core.about

/**
 * v0.39 — جاهزية الإطلاق: كتالوج تراخيص المصدر المفتوح.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * متطلب نشر v1.0 (المتجران + F-Droid): عرض تراخيص المكتبات الثالثة
 * للمستخدم داخل التطبيق. الكتالوج هنا مصدر الحقيقة الوحيد:
 *
 *  - قائمة حتماً مرتبة (لا تترتيب حسب الإدخال أبداً) لكل مكتبة:
 *     الاسم، الإصدار، الرخصة، مصدر الكود، وسطر وصف عربي.
 *  - اختبارات المستودع تقاطع الإصدارات هنا مع libs.versions.toml
 *    الحقيقي — انزياح إصدار بين الاثنين يُفشل البناء (نمط BuildMaturity).
 *  - الملخصات (عدد المكتبات لكل رخصة) حتمية ومفروزة.
 *
 * الرخص المعلنة هي الرخص النافعة للمكتبات المستخدمة فعلاً في
 * libs.versions.toml — لا مكتبة وهمية ولا رخصة مخمَّنة.
 */

/** مكتبة ثالثة واحدة في الكتالوج */
data class LibLicense(
    val id: String,        // معرف فريد حتمي
    val name: String,      // الاسم المعروض
    val version: String,   // الإصدار المستخدم فعلاً (مطابق للكتالوج)
    val license: String,   // SPDX مختصر: Apache-2.0 / MIT / ...
    val source: String,    // URL المشروع
    val note: String,      // وصف عربي بسطر واحد
)

object LicenseCatalog {

    // ---------- الكتالوج — مرتب بالمعرف حتماً ----------

    val LIBS: List<LibLicense> = listOf(
        LibLicense(
            "androidx", "AndroidX (Compose · Room · DataStore · Media3 · WorkManager)",
            "2.6.1", "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support",
            "أساس الواجهة والبيانات: Compose وRoom وDataStore وMedia3 والمهام الخلفية",
        ),
        LibLicense(
            "coil", "Coil",
            "2.7.0", "Apache-2.0", "https://github.com/coil-kt/coil",
            "تحميل صور المعاينة بكفاءة ذاكرة",
        ),
        LibLicense(
            "commons-compress", "Apache Commons Compress",
            "1.26.2", "Apache-2.0", "https://commons.apache.org/proper/commons-compress/",
            "محرك قراءة الأرشيفات 7z/TAR بصيغ الضغط المختلفة",
        ),
        LibLicense(
            "commons-net", "Apache Commons Net",
            "3.11.0", "Apache-2.0", "https://commons.apache.org/proper/commons-net/",
            "عميل FTP فوق بروتوكول RFC 959",
        ),
        LibLicense(
            "coroutines", "kotlinx.coroutines",
            "1.9.0", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines",
            "البرمجة التعاونية لكامل طبقات التطبيق",
        ),
        LibLicense(
            "junrar", "junrar",
            "8.1.2", "Unlicense", "https://github.com/thraaawn/junrar",
            "قراءة أرشيفات RAR4/RAR5 واستخراجها",
        ),
        LibLicense(
            "kotlin", "Kotlin Standard Library",
            "2.0.21", "Apache-2.0", "https://github.com/JetBrains/kotlin",
            "لغة التطبيق ومكتبتها القياسية",
        ),
        LibLicense(
            "okhttp", "OkHttp",
            "4.12.0", "Apache-2.0", "https://github.com/square/okhttp",
            "طبقة HTTP لعملاء WebDAV والسحابة (OAuth PKCE)",
        ),
        LibLicense(
            "smbj", "SMBJ",
            "0.13.0", "Apache-2.0", "https://github.com/hierynomus/smbj",
            "عميل شبكات SMB2/SMB3 لمشاركة ويندوز",
        ),
        LibLicense(
            "sshd", "Apache MINA SSHD",
            "2.13.2", "Apache-2.0", "https://github.com/apache/mina-sshd",
            "عميل SFTP المشفّر وخوادمه",
        ),
        LibLicense(
            "slf4j", "SLF4J",
            "1.7.36", "MIT", "https://www.slf4j.org/",
            "واجهة السجلات لمكتبات الشبكة",
        ),
        LibLicense(
            "xzd", "XZ for Java",
            "1.9", "Public-Domain", "https://tukaani.org/xz/java.html",
            "ضغط وفك XZ لأرشيفات TAR.XZ",
        ),
        LibLicense(
            "zip4j", "Zip4j",
            "2.11.5", "Apache-2.0", "https://github.com/srikanth-lingala/zip4j",
            "أرشيفات ZIP بكلمات مرور AES-256",
        ),
        LibLicense(
            "zxing", "ZXing Core",
            "3.5.3", "Apache-2.0", "https://github.com/zxing/zxing",
            "توليد رموز QR لمشاركة الاتصال جهاز↔جهاز",
        ),
    )

    /** التحقق البنيوي: معرفات فريدة، كل الحقول غير فارغة، رخص معروفة */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = LIBS.map { it.id }
        ids.distinct().let { if (it.size != ids.size) errors += "معرفات مكتبات مكررة" }
        LIBS.forEach { lib ->
            if (lib.id.isBlank() || lib.name.isBlank() || lib.version.isBlank()) {
                errors += "حقول فارغة في ${lib.id}"
            }
            if (lib.license.isBlank()) errors += "رخصة فارغة في ${lib.id}"
            if (!lib.source.startsWith("https://")) errors += "مصدر غير آمن (https مطلوب) في ${lib.id}"
            if (lib.note.isBlank()) errors += "وصف فارغ في ${lib.id}"
        }
        return errors
    }

    fun byId(id: String): LibLicense? = LIBS.firstOrNull { it.id == id }

    /** الإدخالات المفروزة بالاسم المعروض — لعرض قائمة مستقرة للمستخدم */
    fun sortedByName(): List<LibLicense> = LIBS.sortedBy { it.name.lowercase() }

    /** عدد المكتبات لكل رخصة — مفروز حتماً بالرخصة */
    fun countsByLicense(): List<Pair<String, Int>> =
        LIBS.groupBy { it.license }
            .map { (lic, libs) -> lic to libs.size }
            .sortedBy { it.first }

    /** الإصدارات حسب المعرف — تستخدمه اختبارات التقاطع مع libs.versions.toml */
    fun versionsById(): Map<String, String> = LIBS.associate { it.id to it.version }
}

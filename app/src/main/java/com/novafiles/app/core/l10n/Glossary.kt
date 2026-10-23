package com.novafiles.app.core.l10n

/**
 * v0.37 — جسر الإطلاق: القاموس الموحد للمصطلحات عبر اللغات العشر.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * الهدف (بند الخارطة: «قاموس مصطلحات موحد»): مصطلحات NovaFiles الجوهرية
 * تُترجم بترجمة معتمدة واحدة لكل لغة، بحيث لا يظهر «أرشيف» بكلمتين
 * مختلفتين في شاشتين. المحرك يفرض أربع قواعد:
 *  1. الاكتمال: كل مصطلح مترجم في العشر لغات كلها بلا فراغات
 *  2. صحة النظام: RTL (ar/ur/fa) بحروف عربية، ru بالسيريلية، والبقية لاتينية
 *  3. لا تصادم: مصطلحان مختلفان لا يتشاركان نفس الترجمة داخل اللغة الواحدة
 *  4. لا ترجمة بلغة خاطئة: لا سيريلية خارج ru، ولا لاتينية داخل RTL
 */
data class GlossaryTerm(val key: String, val translations: Map<String, String>)

sealed class GlossaryIssue {
    abstract val locale: String

    data class MissingTerm(override val locale: String, val termKey: String) : GlossaryIssue()
    data class BlankTerm(override val locale: String, val termKey: String) : GlossaryIssue()
    data class WrongScript(override val locale: String, val termKey: String, val detail: String) : GlossaryIssue()
    data class Collision(override val locale: String, val termKeys: List<String>, val shared: String) : GlossaryIssue()
}

object Glossary {

    /** نطاقات يونيكود: حروف عربية وسيريلية — تُستخدم للتحقق من نظام الكتابة */
    private val ARABIC_CHARS = Regex("""[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF\uFB50-\uFDFF\uFE70-\uFEFF]""")
    private val CYRILLIC_CHARS = Regex("""[\u0400-\u04FF]""")
    private val LATIN_CHARS = Regex("""[A-Za-z]""")

    // المصطلحات الجوهرية الثلاثون — ar هو المرجع الدلالي والإنجليزية مرجع المفاتيح
    private val TERMS_MAP: List<Pair<String, Map<String, String>>> = listOf(
        "app" to mapOf(
            "ar" to "التطبيق", "en" to "the app", "fr" to "l'application", "es" to "la aplicación",
            "de" to "die App", "tr" to "uygulama", "id" to "aplikasi", "ur" to "ایپ",
            "fa" to "برنامه", "ru" to "приложение",
        ),
        "file" to mapOf(
            "ar" to "الملف", "en" to "the file", "fr" to "le fichier", "es" to "el archivo",
            "de" to "die Datei", "tr" to "dosya", "id" to "berkas", "ur" to "فائل",
            "fa" to "فایل", "ru" to "файл",
        ),
        "folder" to mapOf(
            "ar" to "المجلد", "en" to "the folder", "fr" to "le dossier", "es" to "la carpeta",
            "de" to "der Ordner", "tr" to "klasör", "id" to "folder", "ur" to "فولڈر",
            "fa" to "پوشه", "ru" to "папка",
        ),
        "archive" to mapOf(
            "ar" to "الأرشيف", "en" to "the archive", "fr" to "l'archive", "es" to "el archivo comprimido",
            "de" to "das Archiv", "tr" to "arşiv", "id" to "arsip", "ur" to "آرکائیو",
            "fa" to "آرشیو", "ru" to "архив",
        ),
        "vault" to mapOf(
            "ar" to "الخزنة", "en" to "the vault", "fr" to "le coffre", "es" to "la bóveda",
            "de" to "der Tresor", "tr" to "kasa", "id" to "brankas", "ur" to "خزانہ",
            "fa" to "گنجینه", "ru" to "сейф",
        ),
        "checksum" to mapOf(
            "ar" to "البصمة", "en" to "the checksum", "fr" to "l'empreinte", "es" to "la suma de verificación",
            "de" to "die Prüfsumme", "tr" to "sağlama", "id" to "checksum", "ur" to "چیک سم",
            "fa" to "اثر انگشت فایل", "ru" to "контрольная сумма",
        ),
        "sync" to mapOf(
            "ar" to "المزامنة", "en" to "sync", "fr" to "la synchronisation", "es" to "la sincronización",
            "de" to "die Synchronisierung", "tr" to "eşitleme", "id" to "sinkronisasi", "ur" to "ہم وقت سازی",
            "fa" to "همگام‌سازی", "ru" to "синхронизация",
        ),
        "favorites" to mapOf(
            "ar" to "المفضلة", "en" to "favorites", "fr" to "les favoris", "es" to "los favoritos",
            "de" to "die Favoriten", "tr" to "favoriler", "id" to "favorit", "ur" to "پسندیدہ",
            "fa" to "علاقه‌مندی‌ها", "ru" to "избранное",
        ),
        "search" to mapOf(
            "ar" to "البحث", "en" to "search", "fr" to "la recherche", "es" to "la búsqueda",
            "de" to "die Suche", "tr" to "arama", "id" to "penelusuran", "ur" to "تلاش",
            "fa" to "جستجو", "ru" to "поиск",
        ),
        "settings" to mapOf(
            "ar" to "الإعدادات", "en" to "settings", "fr" to "les paramètres", "es" to "los ajustes",
            "de" to "die Einstellungen", "tr" to "ayarlar", "id" to "pengaturan", "ur" to "ترتیبات",
            "fa" to "تنظیمات", "ru" to "настройки",
        ),
        "terminal" to mapOf(
            "ar" to "الطرفية", "en" to "the terminal", "fr" to "le terminal", "es" to "la terminal",
            "de" to "das Terminal", "tr" to "terminal", "id" to "terminal", "ur" to "ٹرمینل",
            "fa" to "ترمینال", "ru" to "терминал",
        ),
        "trash" to mapOf(
            "ar" to "سلة المحذوفات", "en" to "the trash", "fr" to "la corbeille", "es" to "la papelera",
            "de" to "der Papierkorb", "tr" to "çöp kutusu", "id" to "tempat sampah", "ur" to "کوڑے دان",
            "fa" to "سطل زباله", "ru" to "корзина",
        ),
        "duplicate" to mapOf(
            "ar" to "المكرر", "en" to "the duplicate", "fr" to "le doublon", "es" to "el duplicado",
            "de" to "das Duplikat", "tr" to "kopya", "id" to "duplikat", "ur" to "نقلی",
            "fa" to "تکراری", "ru" to "дубликат",
        ),
        "storage" to mapOf(
            "ar" to "التخزين", "en" to "storage", "fr" to "le stockage", "es" to "el almacenamiento",
            "de" to "der Speicher", "tr" to "depolama", "id" to "penyimpanan", "ur" to "اسٹوریج",
            "fa" to "حافظه", "ru" to "хранилище",
        ),
        "share" to mapOf(
            "ar" to "المشاركة", "en" to "share", "fr" to "le partage", "es" to "compartir",
            "de" to "Teilen", "tr" to "paylaş", "id" to "bagikan", "ur" to "شیئر",
            "fa" to "اشتراک‌گذاری", "ru" to "поделиться",
        ),
        "rename" to mapOf(
            "ar" to "إعادة التسمية", "en" to "rename", "fr" to "renommer", "es" to "renombrar",
            "de" to "Umbenennen", "tr" to "yeniden adlandır", "id" to "ganti nama", "ur" to "نام تبدیل کریں",
            "fa" to "تغییر نام", "ru" to "переименовать",
        ),
        "move" to mapOf(
            "ar" to "النقل", "en" to "move", "fr" to "déplacer", "es" to "mover",
            "de" to "Verschieben", "tr" to "taşı", "id" to "pindahkan", "ur" to "منتقل کریں",
            "fa" to "انتقال", "ru" to "переместить",
        ),
        "copy" to mapOf(
            "ar" to "النسخ", "en" to "copy", "fr" to "copier", "es" to "copiar",
            "de" to "Kopieren", "tr" to "kopyala", "id" to "salin", "ur" to "کاپی",
            "fa" to "کپی", "ru" to "копировать",
        ),
        "delete" to mapOf(
            "ar" to "الحذف", "en" to "delete", "fr" to "supprimer", "es" to "eliminar",
            "de" to "Löschen", "tr" to "sil", "id" to "hapus", "ur" to "حذف کریں",
            "fa" to "حذف", "ru" to "удалить",
        ),
        "encryption" to mapOf(
            "ar" to "التشفير", "en" to "encryption", "fr" to "le chiffrement", "es" to "el cifrado",
            "de" to "die Verschlüsselung", "tr" to "şifreleme", "id" to "enkripsi", "ur" to "خفیہ کاری",
            "fa" to "رمزنگاری", "ru" to "шифрование",
        ),
        "password" to mapOf(
            "ar" to "كلمة المرور", "en" to "the password", "fr" to "le mot de passe", "es" to "la contraseña",
            "de" to "das Passwort", "tr" to "parola", "id" to "kata sandi", "ur" to "پاس ورڈ",
            "fa" to "گذرواژه", "ru" to "пароль",
        ),
        "username" to mapOf(
            "ar" to "اسم المستخدم", "en" to "the username", "fr" to "le nom d'utilisateur", "es" to "el nombre de usuario",
            "de" to "der Benutzername", "tr" to "kullanıcı adı", "id" to "nama pengguna", "ur" to "صارف نام",
            "fa" to "نام کاربری", "ru" to "имя пользователя",
        ),
        "download" to mapOf(
            "ar" to "التنزيل", "en" to "download", "fr" to "le téléchargement", "es" to "la descarga",
            "de" to "der Download", "tr" to "indirme", "id" to "unduhan", "ur" to "ڈاؤن لوڈ",
            "fa" to "دانلود", "ru" to "загрузка",
        ),
        "upload" to mapOf(
            "ar" to "الرفع", "en" to "upload", "fr" to "l'envoi", "es" to "la subida",
            "de" to "das Hochladen", "tr" to "yükleme", "id" to "unggahan", "ur" to "اپ لوڈ",
            "fa" to "بارگذاری", "ru" to "отправка",
        ),
        "server" to mapOf(
            "ar" to "الخادم", "en" to "the server", "fr" to "le serveur", "es" to "el servidor",
            "de" to "der Server", "tr" to "sunucu", "id" to "server", "ur" to "سرور",
            "fa" to "سرور", "ru" to "сервер",
        ),
        "network" to mapOf(
            "ar" to "الشبكة", "en" to "the network", "fr" to "le réseau", "es" to "la red",
            "de" to "das Netzwerk", "tr" to "ağ", "id" to "jaringan", "ur" to "نیٹ ورک",
            "fa" to "شبکه", "ru" to "сеть",
        ),
        "size" to mapOf(
            "ar" to "الحجم", "en" to "size", "fr" to "la taille", "es" to "el tamaño",
            "de" to "die Größe", "tr" to "boyut", "id" to "ukuran", "ur" to "سائز",
            "fa" to "اندازه", "ru" to "размер",
        ),
        "date" to mapOf(
            "ar" to "التاريخ", "en" to "date", "fr" to "la date", "es" to "la fecha",
            "de" to "das Datum", "tr" to "tarih", "id" to "tanggal", "ur" to "تاریخ",
            "fa" to "تاریخ", "ru" to "дата",
        ),
        "preview" to mapOf(
            "ar" to "المعاينة", "en" to "preview", "fr" to "l'aperçu", "es" to "la vista previa",
            "de" to "die Vorschau", "tr" to "önizleme", "id" to "pratinjau", "ur" to "پیش نظارہ",
            "fa" to "پیش‌نمایش", "ru" to "предпросмотр",
        ),
        "cloud" to mapOf(
            "ar" to "السحابة", "en" to "the cloud", "fr" to "le cloud", "es" to "la nube",
            "de" to "die Cloud", "tr" to "bulut", "id" to "awan", "ur" to "کلاؤڈ",
            "fa" to "ابر", "ru" to "облако",
        ),
    )

    val TERMS: List<GlossaryTerm> = TERMS_MAP.map { (key, tr) -> GlossaryTerm(key, tr) }

    private val byKey: Map<String, GlossaryTerm> = TERMS.associateBy { it.key }

    /** مصطلح بلغة — يفك الوسم إلى لغة الكتالوج أولاً */
    fun term(key: String, localeTag: String): String? =
        byKey[key]?.translations?.get(LanguageCatalog.byCode(localeTag)?.code ?: localeTag)

    /** كل مصطلحات لغة واحدة (مرتبة بمفتاح المصطلح) */
    fun termsFor(localeTag: String): Map<String, String> {
        val code = LanguageCatalog.byCode(localeTag)?.code ?: localeTag
        return TERMS.associate { it.key to (it.translations[code] ?: "") }
    }

    /**
     * التدقيق الكامل للقاموس — يعيد كل المخالفات (فارغة في العادة عند البيانات
     * الصحيحة). يُستخدم في اختبار يضمن أن بيانات القاموس المدمجة سليمة دائماً.
     */
    fun audit(locales: Set<String> = LanguageCatalog.codes()): List<GlossaryIssue> {
        val issues = mutableListOf<GlossaryIssue>()

        // 1) الاكتمال والفراغ
        for (term in TERMS) {
            for (locale in locales) {
                val value = term.translations[locale]
                when {
                    value == null -> issues += GlossaryIssue.MissingTerm(locale, term.key)
                    value.isBlank() -> issues += GlossaryIssue.BlankTerm(locale, term.key)
                }
            }
        }

        // 2) صحة نظام الكتابة لكل لغة
        for (term in TERMS) {
            for (locale in locales) {
                val value = term.translations[locale] ?: continue
                val script = LanguageCatalog.byCode(locale)?.script
                when (script) {
                    NovaLocale.Script.ARABIC -> if (!ARABIC_CHARS.containsMatchIn(value)) {
                        issues += GlossaryIssue.WrongScript(locale, term.key, "لا حروف عربية في: $value")
                    }
                    NovaLocale.Script.CYRILLIC -> if (!CYRILLIC_CHARS.containsMatchIn(value)) {
                        issues += GlossaryIssue.WrongScript(locale, term.key, "لا حروف سيريلية في: $value")
                    }
                    NovaLocale.Script.LATIN -> if (!LATIN_CHARS.containsMatchIn(value) || ARABIC_CHARS.containsMatchIn(value) || CYRILLIC_CHARS.containsMatchIn(value)) {
                        issues += GlossaryIssue.WrongScript(locale, term.key, "ليست لاتينية صافية في: $value")
                    }
                    null -> issues += GlossaryIssue.WrongScript(locale, term.key, "لغة خارج الكتالوج")
                }
            }
        }

        // 3) لا تصادم داخل اللغة الواحدة (مصطلحان بنفس الترجمة = غموض)
        for (locale in locales) {
            val grouped = TERMS.groupBy({ it.translations[locale] ?: "" }, { it.key })
            for ((translation, keys) in grouped) {
                if (translation.isNotBlank() && keys.size > 1) {
                    issues += GlossaryIssue.Collision(locale, keys.sorted(), translation)
                }
            }
        }
        return issues
    }
}

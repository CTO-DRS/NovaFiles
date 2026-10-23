package com.novafiles.app.core.buildqa

/**
 * v0.37 — جسر الإطلاق: محرك نضج البناء والبناء القابل لإعادة الإنتاج.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM. يفحص نصوص ملفات البناء مباشرة:
 *
 *  1. كتالوج الإصدارات (libs.versions.toml): لا إصدارات ديناميكية
 *     (`+` أو SNAPSHOT أو نطاقات `[1.0,2.0)` أو latest)، وكل version.ref
 *     يشير إلى مفتاح معرّف فعلاً في [versions]
 *  2. gradle.properties: أعلام البناء المطلوبة موجودة
 *  3. إصدار التطبيق: versionName بصيغة دلالية X.Y.Z وversionCode موجباً
 *  4. locales_config.xml: العشر لغات كلها مسجّلة
 *  5. مجلدات القيم values-xx: لكل لغة غير الأساس مجلد مواردها
 */
data class BuildIssue(val rule: String, val detail: String) {
    override fun toString(): String = "$rule: $detail"
}

object BuildMaturityEngine {

    // ---------- 1) كتالوج الإصدارات ----------

    private val DYNAMIC_MARKERS = listOf("+", "SNAPSHOT", "latest", "[", "]", ")", "(")

    /**
     * تدقيق libs.versions.toml — يعيد مخالفات الإصدارات الديناميكية
     * والمراجع الناقصة. البنية المفحوصة هي البنية القياسية لـ TOML
     * المستخدمة في gradle version catalogs.
     */
    fun auditVersionCatalog(toml: String): List<BuildIssue> {
        val issues = mutableListOf<BuildIssue>()
        var section = ""
        val definedVersions = mutableSetOf<String>()

        for (rawLine in toml.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("[") && line.endsWith("]") -> { section = line; continue }

                section == "[versions]" -> {
                    val key = line.substringBefore('=').trim().trim('"')
                    val value = line.substringAfter('=').trim().trim('"').trim('\'')
                    definedVersions += key
                    if (DYNAMIC_MARKERS.any { value.contains(it, ignoreCase = true) }) {
                        issues += BuildIssue("dynamic-version", "إصدار ديناميكي في [$key] = $value")
                    }
                }

                section == "[libraries]" || section == "[plugins]" -> {
                    val key = line.substringBefore('=').trim()
                    val body = line.substringAfter('=').trim()
                    val versionRef = Regex("""version\.ref\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    val inlineVersion = Regex("""version\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    if (versionRef != null && versionRef !in definedVersions) {
                        issues += BuildIssue(
                            "undefined-version-ref",
                            "[$key] يشير إلى version.ref غير معرّف: $versionRef",
                        )
                    }
                    if (inlineVersion != null && DYNAMIC_MARKERS.any { inlineVersion.contains(it, ignoreCase = true) }) {
                        issues += BuildIssue("dynamic-version", "[$key] إصدار ديناميكي: $inlineVersion")
                    }
                }
            }
        }
        return issues
    }

    // ---------- 2) أعلام gradle.properties ----------

    /** الأعلام المطلوبة للبناء القابل للتحكم والتكرار */
    val REQUIRED_GRADLE_FLAGS: Map<String, String> = mapOf(
        "org.gradle.caching" to "true",
        "android.useAndroidX" to "true",
    )

    fun auditGradleProperties(props: String): List<BuildIssue> {
        val issues = mutableListOf<BuildIssue>()
        val values = props.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        for ((flag, expected) in REQUIRED_GRADLE_FLAGS) {
            val actual = values[flag]
            if (actual == null) {
                issues += BuildIssue("missing-flag", "gradle.properties ينقصه العلم $flag")
            } else if (!actual.equals(expected, ignoreCase = true)) {
                issues += BuildIssue("bad-flag-value", "العلم $flag يجب أن يكون $expected وليس $actual")
            }
        }
        return issues
    }

    // ---------- 3) إصدار التطبيق ----------

    private val SEMVER_RE = Regex("""^\d+\.\d+\.\d+$""")

    fun auditAppVersion(versionName: String?, versionCode: Int?): List<BuildIssue> {
        val issues = mutableListOf<BuildIssue>()
        if (versionName == null || !SEMVER_RE.matches(versionName)) {
            issues += BuildIssue("bad-version-name", "versionName يجب أن يكون بصيغة X.Y.Z (الموجود: $versionName)")
        }
        if (versionCode == null || versionCode <= 0) {
            issues += BuildIssue("bad-version-code", "versionCode يجب أن يكون عدداً موجباً (الموجود: $versionCode)")
        }
        return issues
    }

    // ---------- 4) locales_config.xml ----------

    private val LOCALE_TAG_RE = Regex("""<locale\s+android:name="([^"]+)""")

    fun auditLocaleConfig(xml: String, required: Set<String>): List<BuildIssue> {
        val declared = LOCALE_TAG_RE.findAll(xml).map { it.groupValues[1].lowercase() }.toSet()
        val missing = required - declared
        return missing.sorted().map {
            BuildIssue("missing-locale", "locales_config ينقصه الوسم $it")
        }
    }

    // ---------- 5) مجلدات الموارد values-xx ----------

    /**
     * كل لغة غير الأساس تحتاج مجلد values-{code} خاصاً بها
     * (الأساس يسكن values/ الافتراضي).
     */
    fun auditResLocaleDirs(dirNames: List<String>, required: Set<String>, baseLocale: String): List<BuildIssue> {
        val present = dirNames.filter { it.startsWith("values-") }
            .map { it.removePrefix("values-").substringBefore("-r").lowercase() }
            .toSet()
        val missing = (required - baseLocale) - present
        return missing.sorted().map {
            BuildIssue("missing-res-dir", "لا مجلد موارد values-$it للغة المطلوبة")
        }
    }
}

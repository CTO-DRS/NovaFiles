package com.novafiles.app.core.smart

/**
 * v0.41.0 — Nova Intelligence المرحلة B: محرك اقتراحات التنظيم (pure Kotlin — صفر IO).
 *
 * أربع قواعد حتمية بأولوية صارمة 1→4، كل اقتراح بسببه ودرجة ثقته ومعاينة نقلات كاملة:
 *  1) OLD_INSTALLERS  — مثبّتات عمرها ≥ 30 يوماً (واحد يكفي) → Installers/
 *  2) LOOSE_ARCHIVES  — أرشيفات مبعثرة ≥ 2                  → Archives/
 *  3) LOOSE_DOCUMENTS — مستندات مبعثرة ≥ 3                  → Documents/
 *  4) LOOSE_MEDIA     — وسائط مبعثرة ≥ 3                    → Media/
 *
 * الضمانات:
 *  — الذيل محفوظ دائماً: النقل فقط، لا إعادة تسمية إطلاقاً.
 *  — تصادم أسماء داخل المجموعة يمنع الاقتراح امتناعاً موثّقاً (org_abstain_conflict).
 *  — ملفات العلامات «ORGTYPE:» تعلن دليلها منظّماً يدوياً فتُتخطى كلياً (org_abstain_marker).
 *  — الأدلة المسمّاة كهدف قياسي تُترك (org_abstain_already).
 *  — الثقة حتمية ضمن [0.50, 0.95] تتصاعد مع حجم المجموعة.
 *  — التجاهل النهائي عبر dismissedKeys بمفاتيح مستقرة ORG:RULE:parent:target.
 */
object OrganizationSuggester {

    /** عمر المثبّت المعتبر قديماً بالأيام */
    const val OLD_INSTALLER_DAYS = 30

    /** العتبات الدنيا لكل قاعدة (عدد الملفات المبعثرة في دليل واحد) */
    const val MIN_ARCHIVES = 2
    const val MIN_DOCUMENTS = 3
    const val MIN_MEDIA = 3

    /** سقف الثقة وأدناها — النطاق مثبّت بالاختبارات */
    const val CONF_BASE = 0.50
    const val CONF_STEP = 0.05
    const val CONF_CAP = 0.95

    /** بادئة ملفات العلامات التي تعلن الدليل «منظّماً يدوياً» */
    const val MARKER_PREFIX = "ORGTYPE:"

    /** ثقة حتمية: 0.50 عند العتبة + 0.05 لكل ملف إضافي، بسقف 0.95 */
    fun confidenceFor(count: Int, threshold: Int): Double =
        (CONF_BASE + CONF_STEP * (count - threshold).coerceAtLeast(0)).coerceAtMost(CONF_CAP)

    /** مفتاح التجاهل المستقر لاقتراح */
    fun keyOf(rule: OrgRule, parentDir: String, targetDir: String): String =
        "ORG:${rule.name}:$parentDir:$targetDir"

    fun suggest(
        rows: List<com.novafiles.app.core.analyzer.SnapshotRow>,
        nowMs: Long,
        dismissedKeys: Set<String> = emptySet(),
        limit: Int = 24,
    ): OrganizationReport {
        val files = rows.filter { !it.isDirectory }
        val byParent = files.groupBy { it.parent }
        val suggestions = ArrayList<OrganizationSuggestion>()
        val abstentions = ArrayList<OrgAbstention>()

        for ((parent, list) in byParent) {
            val parentName = parent.substringAfterLast('/')

            // ── امتناع 1: الدليل نفسه اسمه اسم هدف قياسي — منظّم أصلاً، نتركه ──
            if (parentName in OrgRule.TARGET_NAMES) {
                abstentions += OrgAbstention(parent, "org_abstain_already", parentName)
                continue
            }

            // ── امتناع 2: ملف علامة ORGTYPE: يعلن الدليل منظّماً يدوياً ──
            val marker = list.firstOrNull { it.name.startsWith(MARKER_PREFIX, ignoreCase = true) }
            if (marker != null) {
                abstentions += OrgAbstention(parent, "org_abstain_marker", marker.name)
                continue
            }

            // تجميع مبعثر القاعدة داخل هذا الدليل
            val grouped = HashMap<OrgRule, MutableList<com.novafiles.app.core.analyzer.SnapshotRow>>()
            for (f in list) {
                val rule = ruleFor(f.name, f.modified, nowMs) ?: continue
                grouped.getOrPut(rule) { mutableListOf() }.add(f)
            }

            for (rule in OrgRule.BY_PRIORITY) {
                val members = grouped[rule] ?: continue
                val threshold = thresholdOf(rule)
                if (members.size < threshold) continue

                // ── امتناع 3: تصادم أسماء داخل المجموعة — الهدف نفسه سيستقبل اسماً مكرراً ──
                val names = members.map { it.name }
                if (names.size != names.toSet().size) {
                    val dup = names.groupBy { it }.filterValues { it.size > 1 }.keys.first()
                    abstentions += OrgAbstention(parent, "org_abstain_conflict", dup)
                    continue
                }

                val targetDir = if (parent.isEmpty()) rule.targetDirName else "$parent/${rule.targetDirName}"
                val moves = members
                    .sortedBy { it.path }
                    .map { SuggestedMove(sourcePath = it.path, targetDir = targetDir, fileName = it.name) }
                val key = keyOf(rule, parent, targetDir)
                if (key in dismissedKeys) continue

                suggestions += OrganizationSuggestion(
                    rule = rule,
                    parentDir = parent,
                    targetDir = targetDir,
                    moves = moves,
                    reasonKey = rule.reasonKey,
                    reasonArgs = listOf(members.size.toString(), rule.targetDirName),
                    confidence = confidenceFor(members.size, threshold),
                    key = key,
                )
            }
        }

        // ترتيب حتمي: أولوية القاعدة 1→4 ثم مسار الدليل ألفباًباطاً
        val sorted = suggestions
            .sortedWith(compareBy({ it.rule.priority }, { it.parentDir }))
            .take(limit)
        val abstSorted = abstentions.sortedWith(compareBy({ it.parentDir }, { it.code }, { it.detail }))
        return OrganizationReport(suggestions = sorted, abstentions = abstSorted)
    }

    /** أي قاعدة ينتمي إليها الملف؟ (حتمية من الاسم والعمر فقط) */
    private fun ruleFor(name: String, modified: Long, nowMs: Long): OrgRule? = when {
        DownloadClassifier.classify(name) == DownloadClass.INSTALLER &&
            (nowMs - modified).coerceAtLeast(0L) >= OLD_INSTALLER_DAYS * DownloadClassifier.DAY_MS ->
            OrgRule.OLD_INSTALLERS
        DownloadClassifier.classify(name) == DownloadClass.ARCHIVE -> OrgRule.LOOSE_ARCHIVES
        DownloadClassifier.classify(name) == DownloadClass.DOCUMENT -> OrgRule.LOOSE_DOCUMENTS
        DownloadClassifier.classify(name) == DownloadClass.MEDIA -> OrgRule.LOOSE_MEDIA
        else -> null
    }

    /** عتبة القاعدة الدنيا */
    private fun thresholdOf(rule: OrgRule): Int = when (rule) {
        OrgRule.OLD_INSTALLERS -> 1
        OrgRule.LOOSE_ARCHIVES -> MIN_ARCHIVES
        OrgRule.LOOSE_DOCUMENTS -> MIN_DOCUMENTS
        OrgRule.LOOSE_MEDIA -> MIN_MEDIA
    }
}

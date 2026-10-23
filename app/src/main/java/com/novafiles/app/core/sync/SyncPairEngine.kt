package com.novafiles.app.core.sync

/**
 * v0.32.0 — محرك أزواج المزامنة الخالص (pure JVM).
 *
 * قاعدة الخارطة: لقطات (snapshots) للمجلدات ومقارنة حتمية بالحجم+زمن التعديل
 * (+ بصمة اختيارية من محرك v0.29 للمرشّحات الغامضة)، اتجاه واحد أو مرآة أو
 * ثنائي الاتجاه، وكشف تعارض بالتوقيت مع سياسة صريحة (الأحدث يفوز / الاحتفاظ
 * بالنسختين) — كل القرارات قواعد حتمية مُختبَرة، وبلا أي وصول للشبكة أو القرص.
 *
 * المبدأ: [plan] دالة خالصة تأخذ لقطتين (ولقطة أساس للثنائي الاتجاه) وتعيد
 * خطة تنفيذية كاملة — طبقة التنفيذ (PairSyncRunner) تترجمها إلى IO فعلي.
 *
 * اصطلاح المسارات النسبية: مفاصل «/» بلا شرطة بادئة — «docs/تقارير/2026.pdf».
 */

/** عقدة واحدة في لقطة مجلد — ملف أو مجلد */
data class SyncNode(
    val relativePath: String,
    val size: Long,
    val lastModified: Long,
    val isDir: Boolean,
)

/** اتجاه المزامنة */
enum class SyncDirection {
    /** اتجاه واحد: المصدر ← الوجهة (الغريب في الوجهة يُترك) */
    ONE_WAY,

    /** مرآة: المصدر ← الوجهة + حذف ما ليس في المصدر */
    MIRROR,

    /** ثنائي الاتجاه: الاثنان يتبادلان التغييرات فوق لقطة أساس */
    TWO_WAY,
    ;

    companion object {
        /** تحليل آمن من اسم مخزن — أي قيمة غريبة تعود اتجاهاً واحداً */
        fun fromName(name: String?): SyncDirection =
            entries.firstOrNull { it.name == name } ?: ONE_WAY
    }
}

/** سياسة تعارض ثنائي الاتجاه — صريحة دائماً، لا صمت */
enum class ConflictPolicy {
    /** الأحدث زمنياً يفوز (تعادل: المصدر يفوز حتماً) */
    NEWER_WINS,

    /** الاحتفاظ بالنسختين: الفائز باسمه، والخاسر بلاقة تعارض قبل الامتداد */
    KEEP_BOTH,
    ;

    companion object {
        /** تحليل آمن من اسم مخزن — أي قيمة غريبة تعود للأحدث يفوز */
        fun fromName(name: String?): ConflictPolicy =
            entries.firstOrNull { it.name == name } ?: NEWER_WINS
    }
}

/** أفعال الخطة — الاتجاه مضمّن في الاسم (S=المصدر، D=الوجهة) */
enum class SyncAction {
    /** نسخ جديد من المصدر إلى الوجهة */
    COPY_S2D,

    /** نسخ جديد من الوجهة إلى المصدر (ثنائي الاتجاه) */
    COPY_D2S,

    /** تحديث ملف موجود في الوجهة (نسخ فوقه) */
    UPDATE_S2D,

    /** تحديث ملف موجود في المصدر (ثنائي الاتجاه) */
    UPDATE_D2S,

    /** إنشاء مجلد في الوجهة */
    MKDIR_S2D,

    /** إنشاء مجلد في المصدر (ثنائي الاتجاه) */
    MKDIR_D2S,

    /** حذف من الوجهة */
    DELETE_D,

    /** حذف من المصدر (ثنائي الاتجاه) */
    DELETE_S,

    /** نسخ داخل الوجهة نفسها إلى مسار بديل (نسخة التعارض في KEEP_BOTH) */
    COPY_WITHIN_D,

    /** نسخ داخل المصدر نفسها إلى مسار بديل (KEEP_BOTH في اتجاه D2S) */
    COPY_WITHIN_S,

    /** لا شيء — متطابق */
    SKIP,
}

/** سبب القرار — يُعرض للمستخدم ولا يُخفى (قاعدة الشرح في الخارطة) */
enum class SyncReason {
    NEW_IN_SOURCE,
    NEW_IN_DEST,
    CHANGED_SIZE,
    CHANGED_TIME,
    CHANGED_CONTENT,
    IDENTICAL,
    TYPE_CHANGED,
    MISSING_IN_SOURCE_MIRROR,
    MISSING_IN_SOURCE,
    MISSING_IN_DEST,
    CONFLICT_SOURCE_NEWER,
    CONFLICT_DEST_NEWER,
    CONFLICT_TIE_SOURCE_WINS,
    CONFLICT_MODIFIED_KEPT,
}

/** عنصر خطة واحد */
data class SyncPlanItem(
    val relativePath: String,
    val action: SyncAction,
    val reason: SyncReason,
    /** البايتات المتوقعة للنقل (صفر للحذف/التخطي/المجلدات) */
    val bytes: Long = 0L,
    val isDir: Boolean = false,
    /** مسار نسخة التعارض البديلة لأفعال COPY_WITHIN_* في KEEP_BOTH */
    val conflictCopyPath: String? = null,
)

/** إحصاءات الخطة */
data class SyncPlanStats(
    val toTransfer: Int,
    val toDelete: Int,
    val skipped: Int,
    val conflicts: Int,
    val bytesToTransfer: Long,
    /** اتجاه واحد فقط: عناصر الوجهة الغريبة التي تُركت كما هي */
    val destOnlyKept: Int = 0,
)

/** خطة مزامنة كاملة */
data class SyncPlan(
    val items: List<SyncPlanItem>,
    val stats: SyncPlanStats,
)

/**
 * محرك التخطيط — كل الدوال خالصة.
 */
object SyncPairEngine {

    /** تطبيع مسار نسبي: بلا شرطة بادئة/لاحقة، بلا مفاصل فارغة أو صعود */
    fun normalizePath(raw: String): String =
        raw.trim().replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .joinToString("/")

    /** مسار نسخة التعارض: «docs/report.pdf» ← «docs/report (conflict).pdf» */
    fun conflictPathFor(relativePath: String, label: String = "conflict"): String {
        val p = normalizePath(relativePath)
        if (p.isEmpty()) return p
        val slash = p.lastIndexOf('/')
        val dir = if (slash >= 0) p.substring(0, slash + 1) else ""
        val name = p.substring(slash + 1)
        val dot = name.lastIndexOf('.')
        val marked = if (dot > 0) {
            "${name.substring(0, dot)} ($label)${name.substring(dot)}"
        } else {
            "$name ($label)"
        }
        return "$dir$marked"
    }

    /**
     * بناء الخطة الكاملة.
     *
     * @param source لقطة المصدر
     * @param dest لقطة الوجهة
     * @param direction اتجاه المزامنة
     * @param policy سياسة التعارض (للثنائي الاتجاه)
     * @param baseline لقطة آخر مزامنة ناجحة — للثنائي الاتجاه؛ فارغة = أول جولة
     * @param sourceHashes بصمات اختيارية للمصدر (بمسار نسبي) — تعمّق المقارنة
     *   حين تتطابق الحجم والزمن (نمط v0.29)
     * @param destHashes بصمات اختيارية للوجهة بنفس القاعدة
     * @param conflictLabel اللاقة المستخدمة في مسار نسخة التعارض
     */
    fun plan(
        source: List<SyncNode>,
        dest: List<SyncNode>,
        direction: SyncDirection,
        policy: ConflictPolicy = ConflictPolicy.NEWER_WINS,
        baseline: List<SyncNode> = emptyList(),
        sourceHashes: Map<String, String> = emptyMap(),
        destHashes: Map<String, String> = emptyMap(),
        conflictLabel: String = "conflict",
    ): SyncPlan {
        val sMap = source.mapNotNull { n ->
            val p = normalizePath(n.relativePath)
            if (p.isEmpty()) null else p to n.copy(relativePath = p)
        }.toMap()
        val dMap = dest.mapNotNull { n ->
            val p = normalizePath(n.relativePath)
            if (p.isEmpty()) null else p to n.copy(relativePath = p)
        }.toMap()
        val bMap = baseline.mapNotNull { n ->
            val p = normalizePath(n.relativePath)
            if (p.isEmpty()) null else p to n.copy(relativePath = p)
        }.toMap()

        val items = ArrayList<SyncPlanItem>()
        val conflictPaths = HashSet<String>()
        var toTransfer = 0
        var toDelete = 0
        var skipped = 0
        var bytes = 0L
        var destOnlyKept = 0

        fun add(item: SyncPlanItem) {
            items.add(item)
            if (item.reason.isConflict()) conflictPaths.add(item.relativePath)
            when (item.action) {
                SyncAction.SKIP -> skipped++
                SyncAction.DELETE_D, SyncAction.DELETE_S -> toDelete++
                else -> {
                    toTransfer++
                    bytes += item.bytes
                }
            }
        }

        // الترتيب حتمي: مسارات مفروزة معجمياً — الآباء قبل الأبناء طبيعياً
        val allPaths = (sMap.keys + dMap.keys).toSortedSet()

        for (path in allPaths) {
            val s = sMap[path]
            val d = dMap[path]
            val b = bMap[path]

            when (direction) {
                SyncDirection.ONE_WAY, SyncDirection.MIRROR -> {
                    when {
                        // جديد في المصدر
                        s != null && d == null -> add(
                            if (s.isDir) {
                                SyncPlanItem(path, SyncAction.MKDIR_S2D, SyncReason.NEW_IN_SOURCE, isDir = true)
                            } else {
                                SyncPlanItem(path, SyncAction.COPY_S2D, SyncReason.NEW_IN_SOURCE, s.size)
                            },
                        )

                        s != null && d != null -> when {
                            // عدم تطابق النوع: حذف الوجهة ثم إعادة البناء من المصدر
                            s.isDir != d.isDir -> {
                                add(SyncPlanItem(path, SyncAction.DELETE_D, SyncReason.TYPE_CHANGED, isDir = d.isDir))
                                add(
                                    if (s.isDir) {
                                        SyncPlanItem(path, SyncAction.MKDIR_S2D, SyncReason.TYPE_CHANGED, isDir = true)
                                    } else {
                                        SyncPlanItem(path, SyncAction.COPY_S2D, SyncReason.TYPE_CHANGED, s.size)
                                    },
                                )
                            }

                            s.isDir -> add(SyncPlanItem(path, SyncAction.SKIP, SyncReason.IDENTICAL, isDir = true))

                            // تطابق الميتاداتا → بصمة اختيارية تكشف الفرق الحقيقي
                            s.size == d.size && s.lastModified == d.lastModified -> {
                                val sh = sourceHashes[path]
                                val dh = destHashes[path]
                                if (sh != null && dh != null && sh != dh) {
                                    add(SyncPlanItem(path, SyncAction.UPDATE_S2D, SyncReason.CHANGED_CONTENT, s.size))
                                } else {
                                    add(SyncPlanItem(path, SyncAction.SKIP, SyncReason.IDENTICAL))
                                }
                            }

                            else -> add(
                                SyncPlanItem(
                                    path, SyncAction.UPDATE_S2D,
                                    if (s.size != d.size) SyncReason.CHANGED_SIZE else SyncReason.CHANGED_TIME,
                                    s.size,
                                ),
                            )
                        }

                        // ليس في المصدر
                        else -> when (direction) {
                            SyncDirection.MIRROR -> add(
                                SyncPlanItem(path, SyncAction.DELETE_D, SyncReason.MISSING_IN_SOURCE_MIRROR, isDir = d!!.isDir),
                            )
                            else -> destOnlyKept++ // اتجاه واحد: يُترك كما هو
                        }
                    }
                }

                SyncDirection.TWO_WAY -> {
                    val sEq = s == b // المصدر مطابق للأساس
                    val dEq = d == b // الوجهة مطابقة للأساس

                    when {
                        sEq && dEq -> {
                            // كلاهما مطابق للأساس — لكن تطابق الحجم+الزمن مع بصمتين
                            // مختلفتين يعني تغيّراً خفياً على الجانبين
                            if (s != null && d != null && !s.isDir &&
                                sourceHashes[path] != null && destHashes[path] != null &&
                                sourceHashes[path] != destHashes[path]
                            ) {
                                resolveConflict(path, s, d, policy, conflictLabel, ::add)
                            }
                            // وإلا لا شيء — متطابقان فعلاً (لا نُصدر عنصراً)
                        }

                        // تغيّر الوجهة فقط → انشر إلى المصدر
                        sEq && !dEq -> when {
                            d == null -> add(SyncPlanItem(path, SyncAction.DELETE_S, SyncReason.MISSING_IN_DEST, isDir = b?.isDir ?: false))
                            s == null -> add(
                                if (d.isDir) {
                                    SyncPlanItem(path, SyncAction.MKDIR_D2S, SyncReason.NEW_IN_DEST, isDir = true)
                                } else {
                                    SyncPlanItem(path, SyncAction.COPY_D2S, SyncReason.NEW_IN_DEST, d.size)
                                },
                            )
                            s.isDir != d.isDir -> {
                                add(SyncPlanItem(path, SyncAction.DELETE_S, SyncReason.TYPE_CHANGED, isDir = s.isDir))
                                add(
                                    if (d.isDir) {
                                        SyncPlanItem(path, SyncAction.MKDIR_D2S, SyncReason.TYPE_CHANGED, isDir = true)
                                    } else {
                                        SyncPlanItem(path, SyncAction.COPY_D2S, SyncReason.TYPE_CHANGED, d.size)
                                    },
                                )
                            }
                            else -> add(SyncPlanItem(path, SyncAction.UPDATE_D2S, pickChange(s, d), d.size))
                        }

                        // تغيّر المصدر فقط → انشر إلى الوجهة
                        !sEq && dEq -> when {
                            s == null -> add(SyncPlanItem(path, SyncAction.DELETE_D, SyncReason.MISSING_IN_SOURCE, isDir = b?.isDir ?: false))
                            d == null -> add(
                                if (s.isDir) {
                                    SyncPlanItem(path, SyncAction.MKDIR_S2D, SyncReason.NEW_IN_SOURCE, isDir = true)
                                } else {
                                    SyncPlanItem(path, SyncAction.COPY_S2D, SyncReason.NEW_IN_SOURCE, s.size)
                                },
                            )
                            s.isDir != d.isDir -> {
                                add(SyncPlanItem(path, SyncAction.DELETE_D, SyncReason.TYPE_CHANGED, isDir = d.isDir))
                                add(
                                    if (s.isDir) {
                                        SyncPlanItem(path, SyncAction.MKDIR_S2D, SyncReason.TYPE_CHANGED, isDir = true)
                                    } else {
                                        SyncPlanItem(path, SyncAction.COPY_S2D, SyncReason.TYPE_CHANGED, s.size)
                                    },
                                )
                            }
                            else -> add(SyncPlanItem(path, SyncAction.UPDATE_S2D, pickChange(s, d), s.size))
                        }

                        // تغيّر على الجانبين — منطقة التعارض
                        else -> when {
                            s == null && d == null -> {
                                // حُذف على الجانبين — لا شيء
                            }

                            // حذف في المصدر مقابل تعديل في الوجهة: التعديل يحفظ المحتوى
                            s == null -> add(SyncPlanItem(path, SyncAction.COPY_D2S, SyncReason.CONFLICT_MODIFIED_KEPT, d!!.size))
                            d == null -> add(SyncPlanItem(path, SyncAction.COPY_S2D, SyncReason.CONFLICT_MODIFIED_KEPT, s!!.size))

                            // تقاربا إلى الحالة نفسها
                            s == d -> {}

                            // خلط مجلد/ملف على الجانبين — الأبناء يُعالجون مسارياً؛
                            // المسار نفسه لا قرار بنيوياً آمناً هنا
                            s!!.isDir || d!!.isDir -> {}

                            else -> resolveConflict(path, s!!, d!!, policy, conflictLabel, ::add)
                        }
                    }
                }
            }
        }

        return SyncPlan(
            items = items,
            stats = SyncPlanStats(
                toTransfer = toTransfer,
                toDelete = toDelete,
                skipped = skipped,
                conflicts = conflictPaths.size,
                bytesToTransfer = bytes,
                destOnlyKept = destOnlyKept,
            ),
        )
    }

    /** سبب التغيير بين عقدتين من نفس النوع */
    private fun pickChange(a: SyncNode, b: SyncNode): SyncReason =
        if (a.size != b.size) SyncReason.CHANGED_SIZE else SyncReason.CHANGED_TIME

    /**
     * فصل التعارض بين ملفين مختلفين على الجانبين — حتمي بالكامل:
     *  - NEWER_WINS: الأحدث زمنياً يفوز، والتعادل للمصدر (قاعدة موثقة)
     *  - KEEP_BOTH: الفائز يُنسخ فوق الخاسر، والخاسر يُحفظ مسبقاً بنسخة داخلية
     *    بمسار بلاقة التعارض قبل الكتابة فوقه
     */
    private fun resolveConflict(
        path: String,
        s: SyncNode,
        d: SyncNode,
        policy: ConflictPolicy,
        label: String,
        add: (SyncPlanItem) -> Unit,
    ) {
        val sourceNewer = s.lastModified > d.lastModified
        val tie = s.lastModified == d.lastModified

        when (policy) {
            ConflictPolicy.NEWER_WINS -> when {
                sourceNewer || tie -> add(
                    SyncPlanItem(
                        path, SyncAction.UPDATE_S2D,
                        if (tie) SyncReason.CONFLICT_TIE_SOURCE_WINS else SyncReason.CONFLICT_SOURCE_NEWER,
                        s.size,
                    ),
                )
                else -> add(SyncPlanItem(path, SyncAction.UPDATE_D2S, SyncReason.CONFLICT_DEST_NEWER, d.size))
            }

            ConflictPolicy.KEEP_BOTH -> {
                val sourceWins = sourceNewer || tie
                if (sourceWins) {
                    // انسخ نسخة الوجهة (الخاسر) داخل الوجهة أولاً ثم اكتب فوقها
                    add(
                        SyncPlanItem(
                            path, SyncAction.COPY_WITHIN_D, SyncReason.CONFLICT_MODIFIED_KEPT,
                            conflictCopyPath = conflictPathFor(path, label),
                        ),
                    )
                    add(
                        SyncPlanItem(
                            path, SyncAction.UPDATE_S2D,
                            if (tie) SyncReason.CONFLICT_TIE_SOURCE_WINS else SyncReason.CONFLICT_SOURCE_NEWER,
                            s.size,
                        ),
                    )
                } else {
                    add(
                        SyncPlanItem(
                            path, SyncAction.COPY_WITHIN_S, SyncReason.CONFLICT_MODIFIED_KEPT,
                            conflictCopyPath = conflictPathFor(path, label),
                        ),
                    )
                    add(SyncPlanItem(path, SyncAction.UPDATE_D2S, SyncReason.CONFLICT_DEST_NEWER, d.size))
                }
            }
        }
    }
}

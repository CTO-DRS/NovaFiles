package com.novafiles.app.core.sync

import java.io.IOException
import java.io.InputStream

/**
 * v0.32.0 — منفّذ خطة زوج المزامنة (جسر المحرك الخالص إلى IO الجانبين).
 *
 * التدفق: لقطة كل جانب → خطة [SyncPairEngine] → (بصمات تعميق اختيارية
 * للمرشّحات المتطابقة ميتاداتياً) → تنفيذ العناصر واحداً واحداً بإلغاء
 * تعاوني بين العناصر، ثم التحقق بالبصمة بعد النسخ إن طُلب (نمط v0.29)،
 * وضبط زمن التعديل على الجانب المستلم (أفضل جهد) ليستقر الحكم في
 * الجولات القادمة على الميتاداتا وحدها.
 *
 * لا يخزّن شيئاً بنفسه — التقرير وطبقة الأساس (baseline) للثنائي
 * الاتجاه تحفظهما طبقة Room عبر [com.novafiles.app.core.sync.SyncWorker].
 */
class PairSyncRunner(
    private val source: SyncSide,
    private val dest: SyncSide,
    private val isCancelled: () -> Boolean = { false },
    private val onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
) {

    /** نتيجة جولة كاملة */
    data class Outcome(
        val plan: SyncPlan,
        val report: PairRunReport,
        /** لقطة الأساس للجولة القادمة — فقط عند نجاح كامل وثنائي الاتجاه */
        val baselineSnapshot: List<SyncNode>? = null,
        /** خطأ قاتل قبل بدء التنفيذ (فشل لقطات مثلاً) */
        val error: String? = null,
    ) {
        val fatal: Boolean get() = error != null
    }

    suspend fun run(
        direction: SyncDirection,
        policy: ConflictPolicy,
        baseline: List<SyncNode> = emptyList(),
        verifyChecksums: Boolean = false,
    ): Outcome {
        val sourceSnap = runCatching { source.snapshot() }
            .getOrElse { return fatalOutcome(direction, it) }
        val destSnap = runCatching { dest.snapshot() }
            .getOrElse { return fatalOutcome(direction, it) }

        var plan = SyncPairEngine.plan(sourceSnap, destSnap, direction, policy, baseline)
        var sourceHashes: Map<String, String> = emptyMap()
        var destHashes: Map<String, String> = emptyMap()

        // ═══ تعميق اختياري: بصمات المرشّحات المتطابقة ميتاداتياً ═══
        if (verifyChecksums) {
            val deepPaths = plan.items
                .filter { it.action == SyncAction.SKIP && !it.isDir }
                .map { it.relativePath }
            if (deepPaths.isNotEmpty()) {
                sourceHashes = hashQuietly(source, deepPaths)
                destHashes = hashQuietly(dest, deepPaths)
                plan = SyncPairEngine.plan(
                    sourceSnap, destSnap, direction, policy, baseline,
                    sourceHashes, destHashes,
                )
            }
        }

        val sMap = sourceSnap.associateBy { it.relativePath }
        val dMap = destSnap.associateBy { it.relativePath }
        val actionable = plan.items.filter { it.action != SyncAction.SKIP }
        val total = actionable.size

        val conflictPaths = HashSet<String>()
        var copied = 0
        var updated = 0
        var deleted = 0
        var failed = 0
        var bytesDone = 0L
        var verifiedOk = 0
        var verifyFailed = 0
        var cancelled = false

        for ((index, item) in actionable.withIndex()) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            onProgress(index, total, item.relativePath)
            try {
                when (item.action) {
                    SyncAction.SKIP -> { /* مُستبعد مسبقاً */ }

                    SyncAction.MKDIR_S2D -> dest.mkdirs(item.relativePath)
                    SyncAction.MKDIR_D2S -> source.mkdirs(item.relativePath)

                    SyncAction.DELETE_D -> {
                        dest.delete(item.relativePath, item.isDir)
                        deleted++
                    }
                    SyncAction.DELETE_S -> {
                        source.delete(item.relativePath, item.isDir)
                        deleted++
                    }

                    SyncAction.COPY_WITHIN_D -> {
                        dest.copyWithin(
                            item.relativePath,
                            requireNotNull(item.conflictCopyPath) { "conflict copy path missing" },
                        )
                        conflictPaths.add(item.relativePath)
                    }
                    SyncAction.COPY_WITHIN_S -> {
                        source.copyWithin(
                            item.relativePath,
                            requireNotNull(item.conflictCopyPath) { "conflict copy path missing" },
                        )
                        conflictPaths.add(item.relativePath)
                    }

                    SyncAction.COPY_S2D, SyncAction.UPDATE_S2D -> {
                        val node = sMap[item.relativePath]
                            ?: throw IOException("source node vanished: ${item.relativePath}")
                        val from = source
                        val to = dest
                        val toPath = item.relativePath
                        if (item.reason.isConflict()) conflictPaths.add(item.relativePath)
                        val moved = transfer(from, to, toPath, node, verifyChecksums)
                        if (item.action == SyncAction.COPY_S2D) copied++ else updated++
                        bytesDone += moved.first
                        verifiedOk += moved.second
                        verifyFailed += moved.third
                    }

                    SyncAction.COPY_D2S, SyncAction.UPDATE_D2S -> {
                        val node = dMap[item.relativePath]
                            ?: throw IOException("dest node vanished: ${item.relativePath}")
                        val from = dest
                        val to = source
                        val toPath = item.relativePath
                        if (item.reason.isConflict()) conflictPaths.add(item.relativePath)
                        val moved = transfer(from, to, toPath, node, verifyChecksums)
                        if (item.action == SyncAction.COPY_D2S) copied++ else updated++
                        bytesDone += moved.first
                        verifiedOk += moved.second
                        verifyFailed += moved.third
                    }
                }
            } catch (_: Throwable) {
                failed++
            }
        }
        onProgress(total, total, "")

        val report = PairRunReport(
            copied = copied,
            updated = updated,
            deleted = deleted,
            skipped = plan.stats.skipped,
            conflicts = conflictPaths.size,
            failed = failed,
            bytesTransferred = bytesDone,
            verifiedOk = verifiedOk,
            verifyFailed = verifyFailed,
            cancelled = cancelled,
        )

        // أساس الجولة القادمة: نجاح كامل فقط — الفاشل/المُلغى يبقى للجولة القادمة
        val baselineSnapshot = if (!cancelled && failed == 0 && direction == SyncDirection.TWO_WAY) {
            runCatching { dest.snapshot() }.getOrNull()
        } else {
            null
        }

        return Outcome(plan, report, baselineSnapshot)
    }

    /**
     * نقل ملف واحد من جانب إلى آخر: بصمة مسبقة اختيارية، بث مباشر،
     * تحقق بعد النسخ، وضبط زمن التعديل (أفضل جهد).
     * @return (البايتات المنقولة، تحقق ناجح 0/1، تحقق فاشل 0/1)
     */
    private suspend fun transfer(
        from: SyncSide,
        to: SyncSide,
        relativePath: String,
        node: SyncNode,
        verify: Boolean,
    ): Triple<Long, Int, Int> {
        var srcHash: String? = null
        if (verify) {
            srcHash = hashQuietly(from, listOf(relativePath))[relativePath]
                ?: throw IOException("cannot hash source: $relativePath")
        }
        var written = 0L
        val input: InputStream = from.openInput(relativePath)
        try {
            written = to.write(relativePath, node.size, input)
        } finally {
            runCatching { input.close() }
        }
        to.setModified(relativePath, node.lastModified)

        if (verify && srcHash != null) {
            val destHash = hashQuietly(to, listOf(relativePath))[relativePath]
            return if (destHash != null && destHash == srcHash) {
                Triple(written, 1, 0)
            } else {
                Triple(written, 0, 1)
            }
        }
        return Triple(written, 0, 0)
    }

    /** بصمات متدفقة لمسارات — الفاشل يعود بلا بصمة ولا يوقف الدفعة */
    private suspend fun hashQuietly(side: SyncSide, paths: List<String>): Map<String, String> {
        val out = HashMap<String, String>(paths.size)
        for (p in paths) {
            if (isCancelled()) break
            runCatching {
                side.openInput(p).use { stream ->
                    out[p] = sha256Hex(stream)
                }
            }
        }
        return out
    }

    /** SHA-256 متدفق بمخزن 64KB — نفس نمط مزامنة الكاميرا v0.16 */
    private fun sha256Hex(input: InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fatalOutcome(direction: SyncDirection, t: Throwable): Outcome = Outcome(
        plan = SyncPairEngine.plan(emptyList(), emptyList(), direction),
        report = PairRunReport.EMPTY,
        error = t.message ?: t.javaClass.simpleName,
    )
}

/** هل سبب العنصر من عائلة التعارض؟ */
internal fun SyncReason.isConflict(): Boolean = when (this) {
    SyncReason.CONFLICT_SOURCE_NEWER,
    SyncReason.CONFLICT_DEST_NEWER,
    SyncReason.CONFLICT_TIE_SOURCE_WINS,
    SyncReason.CONFLICT_MODIFIED_KEPT,
    -> true
    else -> false
}

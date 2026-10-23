package com.novafiles.app.core.rules

import com.novafiles.app.core.cloud.MiniJson
import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.fs.FileType

/**
 * v0.35.0 — ترميز/فك ترميز القواعد إلى JSON خالص.
 *
 * التسلسل كاتب JSON مصغّر خاص (MiniJson للقراءة فقط)، وفك الترميم متسامح:
 * JSON معطوب → قائمة فارغة، حقول ناقصة → قيم افتراضية، أفعال مجهولة → تُتجاهل
 * (توافق أمامي مع إصدارات أحدث). يُخزَّن النص في DataStore عبر RulesRepository.
 */
object RuleCodec {

    // ─────────────────────────── الترميز ───────────────────────────

    fun encode(rules: List<WatchRule>): String {
        val sb = StringBuilder()
        sb.append('[')
        rules.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(q(r.id)).append(',')
            sb.append("\"name\":").append(q(r.name)).append(',')
            sb.append("\"watch\":").append(q(r.watchFolder)).append(',')
            sb.append("\"enabled\":").append(r.enabled).append(',')
            sb.append("\"dryRun\":").append(r.dryRun).append(',')
            sb.append("\"createdAt\":").append(r.createdAt).append(',')
            r.lastRunAt?.let { sb.append("\"lastRunAt\":").append(it).append(',') }
            sb.append("\"runCount\":").append(r.runCount).append(',')
            sb.append("\"cond\":{")
                .append("\"glob\":").append(q(r.conditions.nameGlob.orEmpty())).append(',')
                .append("\"types\":[").append(r.conditions.types.joinToString(",") { q(it.name) }).append("],")
                .append("\"min\":").append(r.conditions.minSizeBytes ?: "null").append(',')
                .append("\"max\":").append(r.conditions.maxSizeBytes ?: "null")
                .append("},")
            sb.append("\"actions\":[")
            r.actions.forEachIndexed { j, a ->
                if (j > 0) sb.append(',')
                sb.append(encodeAction(a))
            }
            sb.append(']')
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun encodeAction(a: RuleAction): String = when (a) {
        is RuleAction.MoveTo -> "{\"k\":\"move\",\"to\":${q(a.targetDir)}}"
        is RuleAction.ArchiveZip ->
            "{\"k\":\"zip\",\"name\":${q(a.namePattern)},\"append\":${a.append},\"del\":${a.deleteAfter}}"
        is RuleAction.Checksum -> "{\"k\":\"sum\",\"algo\":${q(a.algorithm.name)}}"
        is RuleAction.TagFile -> "{\"k\":\"tag\",\"tag\":${q(a.tag)}}"
        is RuleAction.UploadCloud ->
            "{\"k\":\"cloud\",\"acct\":${a.accountId},\"dir\":${q(a.remoteDir)}}"
    }

    /** سلسلة JSON مُهرَّبة كاملة: اقتباس، خط مائل عكسي، محارف تحكم → \uXXXX */
    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun q(s: String): String = "\"" + esc(s) + "\""

    // ─────────────────────────── فك الترميم ───────────────────────────

    fun decode(text: String): List<WatchRule> = try {
        val arr = MiniJson.parse(text)
        (arr as? MiniJson.Value.Arr)?.items?.mapNotNull { decodeRule(it) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun decodeRule(v: MiniJson.Value): WatchRule? {
        val o = MiniJson.asObject(v)
        val id = MiniJson.asString(o["id"]) ?: return null
        val cond = MiniJson.asObject(o["cond"])
        val actions = MiniJson.asArray(o["actions"]).mapNotNull { decodeAction(it) }
        return WatchRule(
            id = id,
            name = MiniJson.asString(o["name"]).orEmpty(),
            watchFolder = MiniJson.asString(o["watch"]).orEmpty(),
            conditions = RuleConditions(
                nameGlob = MiniJson.asString(cond["glob"])?.takeIf { it.isNotEmpty() },
                types = MiniJson.asArray(cond["types"])
                    .mapNotNull { MiniJson.asString(it) }
                    .mapNotNull { t -> FileType.entries.firstOrNull { it.name == t } }
                    .toSet(),
                minSizeBytes = MiniJson.asLong(cond["min"]),
                maxSizeBytes = MiniJson.asLong(cond["max"]),
            ),
            actions = actions,
            enabled = MiniJson.asBool(o["enabled"]) ?: true,
            dryRun = MiniJson.asBool(o["dryRun"]) ?: false,
            createdAt = MiniJson.asLong(o["createdAt"]) ?: 0L,
            lastRunAt = MiniJson.asLong(o["lastRunAt"]),
            runCount = MiniJson.asLong(o["runCount"])?.toInt() ?: 0,
        )
    }

    private fun decodeAction(v: MiniJson.Value): RuleAction? {
        val o = MiniJson.asObject(v)
        return when (MiniJson.asString(o["k"])) {
            "move" -> MiniJson.asString(o["to"])?.let { RuleAction.MoveTo(it) }
            "zip" -> RuleAction.ArchiveZip(
                namePattern = MiniJson.asString(o["name"]).orEmpty(),
                append = MiniJson.asBool(o["append"]) ?: true,
                deleteAfter = MiniJson.asBool(o["del"]) ?: false,
            )
            "sum" -> MiniJson.asString(o["algo"])?.let { algo ->
                ChecksumAlgorithm.entries.firstOrNull { it.name == algo }
                    ?.let { RuleAction.Checksum(it) }
            }
            "tag" -> MiniJson.asString(o["tag"])?.let { RuleAction.TagFile(it) }
            "cloud" -> MiniJson.asLong(o["acct"])?.let { acct ->
                RuleAction.UploadCloud(acct, MiniJson.asString(o["dir"]).orEmpty())
            }
            else -> null // فعل مجهول (إصدار أحدث) — يُتجاهل بأمان
        }
    }
}

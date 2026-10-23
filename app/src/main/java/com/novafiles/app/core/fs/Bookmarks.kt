package com.novafiles.app.core.fs

import com.novafiles.app.core.cloud.MiniJson

/**
 * v0.36.0 — الإشارات المرجعية للمسارات: منطق خالص + ترميز JSON متسامح.
 *
 * فصل واضح عن المفضلة (favorites في Room): المفضلة للملفات المفتوحة كثيراً،
 * والإشارات المرجعية لمجلدات العمل اليومية في الشجرة الجانبية — وصول أسرع
 * (قسم أعلى الشجرة) مع ترتيب يدوي وسقف 24 مرجعاً.
 *
 * التخزين نص JSON في DataStore (نمط v0.35 RulesRepository) عبر [BookmarksCodec]:
 * JSON تالف → قائمة فارغة، حقول ناقصة → قيم افتراضية، لا استثناءات أبداً.
 */
data class Bookmark(
    val path: String,
    val name: String,
    val addedAt: Long,
)

object BookmarksEngine {

    const val MAX_BOOKMARKS = 24

    /**
     * إضافة مرجع — بلا ازدواج: المسار الموجود يُترك مكانه (لا يتكرر، لا يقفز).
     * القائمة ممتلئة → تُرفض الإضافة (تعيد null) والواجهة تخبر المستخدم بسقفها.
     */
    fun add(
        list: List<Bookmark>,
        path: String,
        name: String,
        now: Long,
        max: Int = MAX_BOOKMARKS,
    ): List<Bookmark>? {
        val p = path.trim()
        if (p.isEmpty()) return list
        if (list.any { it.path == p }) return list // موجود — لا تكرار ولا إعادة ترتيب
        if (list.size >= max) return null
        return list + Bookmark(p, name.trim().ifEmpty { p.substringAfterLast('/') }, now)
    }

    /** إزالة بمسار — إزالة غائبة تعيد القائمة كما هي */
    fun remove(list: List<Bookmark>, path: String): List<Bookmark> =
        list.filterNot { it.path == path.trim() }

    /** هل المسار مرجع؟ (لتلوين زر الإضافة/الإزالة) */
    fun contains(list: List<Bookmark>, path: String): Boolean =
        list.any { it.path == path.trim() }

    /**
     * إعادة ترتيب بنقل عنصر خطوة واحدة: delta = −1 لأعلى، +1 لأسفل.
     * حواف القائمة تعيد القائمة نفسها (لا التفاف دائري — حتمية أوضح).
     */
    fun move(list: List<Bookmark>, path: String, delta: Int): List<Bookmark> {
        val idx = list.indexOfFirst { it.path == path.trim() }
        if (idx < 0) return list
        val target = idx + delta
        if (target < 0 || target >= list.size) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(target, item)
        return mutable
    }

    /** المرجع الأدنى من الأعلى = الأحدث إضافة يبقى آخر القائمة أم أولها؟ الأقدم أولاً (استقرار ترتيب). */
    fun sortedForDisplay(list: List<Bookmark>): List<Bookmark> =
        list.sortedWith(compareBy({ it.addedAt }, { it.path }))
}

/** ترميز/فك ترميز الإشارات إلى JSON خالص (كاتب مصغّر + MiniJson للقراءة) */
object BookmarksCodec {

    fun encode(list: List<Bookmark>): String {
        val sb = StringBuilder()
        sb.append('[')
        list.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":").append(q(b.path))
                .append(",\"n\":").append(q(b.name))
                .append(",\"t\":").append(b.addedAt)
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    /** فك متسامح: فارغ/تالف → قائمة فارغة، مدخلات ناقصة تُتخطى، دائماً بلا مسارات فارغة */
    fun decode(raw: String?): List<Bookmark> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = MiniJson.parse(raw) as? MiniJson.Value.Arr ?: return emptyList()
            arr.items.mapNotNull { item ->
                val obj = (item as? MiniJson.Value.Obj)?.fields ?: return@mapNotNull null
                val p = MiniJson.asString(obj["p"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val n = MiniJson.asString(obj["n"])?.takeIf { it.isNotEmpty() }
                    ?: p.substringAfterLast('/')
                val t = MiniJson.asLong(obj["t"]) ?: 0L
                Bookmark(p, n, t)
            }.distinctBy { it.path }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** اقتباس JSON كامل: اقتباس، خط مائل عكسي، محارف تحكم → \uXXXX (أسماء عربية/إيموجي سليمة) */
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

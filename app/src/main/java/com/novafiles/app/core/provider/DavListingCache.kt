package com.novafiles.app.core.provider

/**
 * v0.13 — ذاكرة سرّد المجلدات البعيدة مع إعادة تحقق ETag.
 *
 * هدف الخارطة التقني: «تصفح 10 آلاف عنصر < 1.5 ثانية ساخناً» — أول فتح
 * يُخزَّن السرد مع ETag المجموعة، والفتحات التالية تطلب خصائص المجموعة
 * وحدها (PROPFIND Depth:0 رخيص) فتُعاد القائمة من الذاكرة إن لم تتغير.
 *
 * LRU بسيط فوق LinkedHashMap(accessOrder=true) — خالص JVM قابل للاختبار.
 */
class DavListingCache(private val maxEntries: Int = 48) {

    data class Cached(val etag: String?, val entries: List<ProviderEntry>)

    private val map = object : LinkedHashMap<String, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>): Boolean =
            size > maxEntries
    }

    /**
     * يعيد سرد المسار من الذاكرة إن كان ETag المجموعة كما هو، وإلا يحمل
     * عبر loader (يعيد ETag+القائمة معاً من طلب واحد) ويخزّن.
     * etag==null من الخادم (بلا دعم ETag) تعطيل التحقق — يُثق بالنسخة
     * المخزنة حتى إبطالها صراحة عبر invalidate.
     */
    suspend fun getOrLoad(
        path: String,
        etagOf: suspend () -> String?,
        loader: suspend () -> Pair<String?, List<ProviderEntry>>,
    ): Pair<List<ProviderEntry>, Boolean> {
        synchronized(map) { map[path] }?.let { cached ->
            val current = etagOf()
            if (current == null || current == cached.etag) return cached.entries to true
        }
        val (etag, fresh) = loader()
        synchronized(map) { map[path] = Cached(etag, fresh) }
        return fresh to false
    }

    /** نسخة مخزنة صريحة بدون تحقق — للاستخدامات المتزامنة (مثل العمليات) */
    fun peek(path: String): List<ProviderEntry>? = synchronized(map) { map[path]?.entries }

    fun put(path: String, etag: String?, entries: List<ProviderEntry>) {
        synchronized(map) { map[path] = Cached(etag, entries) }
    }

    fun invalidate(path: String) = synchronized(map) { map.remove(path) }

    fun clear() = synchronized(map) { map.clear() }

    val size: Int get() = synchronized(map) { map.size }
}

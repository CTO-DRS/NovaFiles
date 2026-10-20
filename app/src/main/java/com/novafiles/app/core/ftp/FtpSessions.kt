package com.novafiles.app.core.ftp

/**
 * v0.27 — سجل جلسات FTP: تتبّع الاتصالات النشطة لحظياً.
 * خالص JVM بالكامل: لا مقابس هنا — ردّ الطلب (kick) يُسلَّم كدالة
 * يحقنها الخادم (تغلق socket الجلسة)، وما عدا ذلك عدّادات وأزمنة.
 */
data class FtpSessionInfo(
    val id: Int,
    val host: String,
    val connectedAt: Long,
    val lastActiveAt: Long,
    val authenticated: Boolean,
)

class FtpSessionRegistry {

    private class Entry(
        val id: Int,
        val host: String,
        val connectedAt: Long,
        var lastActiveAt: Long,
        @Volatile var authenticated: Boolean,
        val closer: () -> Unit,
    )

    private val map = LinkedHashMap<Int, Entry>()
    private var seq = 0

    /** عدد الاتصالات النشطة الآن (بما فيها غير المصادَقة) */
    @get:Synchronized
    val activeCount: Int
        get() = map.size

    @Synchronized
    fun register(host: String, now: Long, closer: () -> Unit): Int {
        val id = ++seq
        map[id] = Entry(id, host, now, now, authenticated = false, closer = closer)
        return id
    }

    @Synchronized
    fun touch(id: Int, now: Long) {
        map[id]?.lastActiveAt = now
    }

    @Synchronized
    fun markAuthenticated(id: Int) {
        map[id]?.authenticated = true
    }

    @Synchronized
    fun unregister(id: Int) {
        map.remove(id)
    }

    /** لقطة الاتصالات الحية — الأقدم أولاً */
    @Synchronized
    fun snapshot(): List<FtpSessionInfo> = map.values.map {
        FtpSessionInfo(it.id, it.host, it.connectedAt, it.lastActiveAt, it.authenticated)
    }

    /** قطع اتصال بعينه من الواجهة — يعيد true إن وُجد */
    @Synchronized
    fun kick(id: Int): Boolean {
        val e = map[id] ?: return false
        runCatching { e.closer() }
        map.remove(id)
        return true
    }

    /** قطع الجميع (يستدعيه إيقاف الخادم) */
    @Synchronized
    fun kickAll() {
        map.values.toList().forEach { runCatching { it.closer() } }
        map.clear()
    }
}

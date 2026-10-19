package com.novafiles.app.core.wifip2p

/**
 * v0.12 — نواة Wi-Fi Direct الخالصة (بلا تبعيات Android).
 *
 * الفلسفة (وفق خارطة الطريق): المرسل يستضيف نواة FTP المدمجة عبر رابط
 * P2P المباشر، ورمز QR يبقى وسيلة المصافحة. تُعزل واجهات WifiP2pManager
 * الأندرويدية خلف مدير رقيق (WifiDirectManager) بينما يعيش كل منطق
 * قابل للاختبار هنا: ترميز الحمولة + مخفّض الحالة.
 */

/** حمولة المصافحة بين الجهازين — تُرمّز في QR أو تُعرض كنص */
data class DirectPayload(
    val host: String,
    val port: Int,
    val deviceName: String,
    val filesReady: Int,
    val user: String = "",
    val pass: String = "",
)

/**
 * ترميز/فك ترميز الحمولة:
 *   nova-direct://192.168.49.1:2121?name=Galaxy%20S24&files=3
 * اسم الجهاز يُرمَّز URL-safe (يدعم العربية والمسافات).
 */
object DirectPayloadCodec {

    const val SCHEME = "nova-direct"

    fun encode(p: DirectPayload): String {
        val name = urlEncode(p.deviceName)
        return buildString {
            append("$SCHEME://${p.host}:${p.port}?name=$name&files=${p.filesReady}")
            if (p.user.isNotEmpty()) append("&user=").append(urlEncode(p.user))
            if (p.pass.isNotEmpty()) append("&pass=").append(urlEncode(p.pass))
        }
    }

    fun decode(raw: String): DirectPayload? {
        val s = raw.trim()
        if (!s.startsWith("$SCHEME://")) return null
        val rest = s.removePrefix("$SCHEME://")
        val authority = rest.substringBefore('?')
        val query = if ('?' in rest) rest.substringAfter('?') else ""
        val hostPort = authority.split(':')
        if (hostPort.size != 2) return null
        val host = hostPort[0]
        val port = hostPort[1].toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null

        val params = query.split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val name = urlDecode(params["name"] ?: "")
        val files = params["files"]?.toIntOrNull() ?: 0
        return DirectPayload(
            host = host,
            port = port,
            deviceName = name,
            filesReady = files,
            user = urlDecode(params["user"] ?: ""),
            pass = urlDecode(params["pass"] ?: ""),
        )
    }

    // ترميز URL مبسّط آمن للرموز — يدعم UTF-8 (العربية)
    internal fun urlEncode(s: String): String = buildString {
        val bytes = s.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c.isUnreserved()) append(c.toChar()) else append('%').append(c.toHexStringUpper())
        }
    }

    internal fun urlDecode(s: String): String {
        if ('%' !in s) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 3 <= s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out += v.toByte(); i += 3; continue }
            }
            out += c.code.toByte()
            i += 1
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun Int.isUnreserved(): Boolean =
        (this in 'a'.code..'z'.code) || (this in 'A'.code..'Z'.code) ||
            (this in '0'.code..'9'.code) || this == '-'.code || this == '.'.code ||
            this == '_'.code || this == '~'.code

    private fun Int.toHexStringUpper(): String {
        val digits = "0123456789ABCDEF"
        val h = (this shr 4) and 0xF
        val l = this and 0xF
        return "${digits[h]}${digits[l]}"
    }
}

/** جار مُكتشف عبر Wi-Fi Direct */
data class DirectPeer(
    val deviceName: String,
    val address: String?,
)

/** أحداث P2P القادمة من طبقة أندرويد الرقيقة */
sealed interface DirectEvent {
    data class PeersChanged(val peers: List<DirectPeer>) : DirectEvent
    data class Connected(val isGroupOwner: Boolean, val ownerAddress: String?) : DirectEvent
    data object Disconnected : DirectEvent
    data class Failed(val reason: String) : DirectEvent
}

/** أطوار الشاشة */
enum class DirectPhase { IDLE, DISCOVERING, CONNECTING, CONNECTED, FAILED }

/** حالة واجهة Wi-Fi Direct */
data class DirectState(
    val phase: DirectPhase = DirectPhase.IDLE,
    val peers: List<DirectPeer> = emptyList(),
    val isGroupOwner: Boolean = false,
    val ownerAddress: String? = null,
    val error: String? = null,
)

/**
 * مخفّض الحالة: يبني DirectState من أحداث P2P.
 * منطق الاتصال المعتمد:
 *  — المرسل يطلب أن يكون مالك المجموعة (groupOwnerIntent = 15) ثم يستضيف FTP.
 *  — المستقبل يتصل كعضو ويعرف عنوان المالك دائماً عبر requestConnectionInfo.
 */
object DirectStateReducer {

    fun reduce(state: DirectState, event: DirectEvent): DirectState = when (event) {
        is DirectEvent.PeersChanged -> state.copy(
            peers = event.peers,
            phase = if (state.phase == DirectPhase.IDLE || state.phase == DirectPhase.DISCOVERING) {
                DirectPhase.DISCOVERING
            } else state.phase,
        )
        is DirectEvent.Connected -> state.copy(
            phase = DirectPhase.CONNECTED,
            isGroupOwner = event.isGroupOwner,
            ownerAddress = event.ownerAddress,
            error = null,
        )
        is DirectEvent.Disconnected -> DirectState(
            phase = DirectPhase.IDLE,
            peers = state.peers,
        )
        is DirectEvent.Failed -> state.copy(
            phase = DirectPhase.FAILED,
            error = event.reason,
        )
    }
}

package com.novafiles.app.core.wifip2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * v0.12 — المدير الرقيق لواجهات Wi-Fi Direct الأندرويدية.
 *
 * كل استدعاءات WifiP2pManager معزولة هنا؛ المنطق القابل للاختبار يعيش في
 * DirectStateReducer. يعرض تدفّق حالة واجهة (DirectState) فقط.
 *
 * بروتوكول النقل المعتمد:
 *  — المرسل: يكتشف → يتصل بـ groupOwnerIntent = 15 (يفضَّل مالكاً للمجموعة)
 *    → عند الاتصال يبدأ خادم FTP المدمج على واجهة P2P → يعرض QR.
 *  — المستقبل: يتلقى طلب الاقتران (نظام) → عند الاتصال يعرف عنوان المالك
 *    → يتصل بخادم FTP عبر عميل الشبكة الموجود (FtpFs).
 */
class WifiDirectManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    /** أقصى تفضيل لأن نكون مالكي المجموعة عند الإرسال */
    var ownerIntent: Int = 15
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) emit(DirectEvent.Failed("p2p_disabled"))
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> { /* الحالة تُدار محلياً */ }
            }
        }
    }

    private var registered = false

    /** بدء الاكتشاف — يتطلب صلاحيات الموقع/الأجهزة القريبة حسب إصدار النظام */
    fun startDiscovery() {
        val m = manager ?: return emit(DirectEvent.Failed("p2p_unavailable"))
        ensureRegistered()
        _state.value = _state.value.copy(phase = DirectPhase.DISCOVERING, error = null)
        m.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* الانتظار حتى PEERS_CHANGED */ }
            override fun onFailure(reason: Int) { emit(DirectEvent.Failed("discover:$reason")) }
        })
    }

    fun stopDiscovery() {
        manager?.stopPeerDiscovery(channel, null)
    }

    /** الاتصال بجار — كمالك للمجموعة إن كنا نرسل */
    fun connect(peer: DirectPeer, asOwner: Boolean) {
        val m = manager ?: return emit(DirectEvent.Failed("p2p_unavailable"))
        if (peer.address.isNullOrEmpty()) return emit(DirectEvent.Failed("peer_no_address"))
        _state.value = _state.value.copy(phase = DirectPhase.CONNECTING)
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            wps.setup = WpsInfo.PBC
            if (asOwner) {
                // واجهة جديدة فقط — قديمة تُتجاهل بأمان
                @Suppress("DEPRECATION")
                groupOwnerIntent = ownerIntent
            }
        }
        m.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* CONNECTION_CHANGED سيعرفنا الحالة */ }
            override fun onFailure(reason: Int) { emit(DirectEvent.Failed("connect:$reason")) }
        })
    }

    fun disconnect() {
        manager?.removeGroup(channel, null)
        _state.value = DirectState(peers = _state.value.peers)
    }

    fun release() {
        runCatching { manager?.removeGroup(channel, null) }
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        // v0.41.1 — Channel.close متاح من API 27 فقط؛ على API 26 تُحرّر القناة مع إلغاء
        // تسجيل المستقبل وموت العملية (لا تسريب موارد ملموس في دورة حياة الخدمة)
        runCatching { if (android.os.Build.VERSION.SDK_INT >= 27) channel?.close() }
        channel = null
    }

    // ----- استعلامات داخلية -----

    private fun requestPeers() {
        manager?.requestPeers(channel) { peers: android.net.wifi.p2p.WifiP2pDeviceList ->
            val list = peers.deviceList.map { device: WifiP2pDevice ->
                DirectPeer(
                    deviceName = device.deviceName.ifEmpty { device.deviceAddress },
                    address = device.deviceAddress,
                )
            }
            emit(DirectEvent.PeersChanged(list))
        }
    }

    private fun requestConnectionInfo() {
        manager?.requestConnectionInfo(channel) { info: android.net.wifi.p2p.WifiP2pInfo ->
            if (info.groupFormed) {
                emit(DirectEvent.Connected(isGroupOwner = info.isGroupOwner, ownerAddress = info.groupOwnerAddress?.hostAddress))
            } else {
                emit(DirectEvent.Disconnected)
            }
        }
    }

    private fun ensureRegistered() {
        if (registered) return
        channel = manager?.initialize(context, context.mainLooper, null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter)
        registered = true
    }

    private fun emit(event: DirectEvent) {
        scope.launch { _state.value = DirectStateReducer.reduce(_state.value, event) }
    }

    /** هل الأذونات اللازمة متوفرة حسب إصدار النظام؟ */
    fun hasRequiredPermissions(): Boolean {
        val ctx = context
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    companion object {
        /** أدنى إصدار يدعم Wi-Fi Direct عملياً في التطبيق */
        const val MIN_P2P_PORT = 1024
        const val DEFAULT_FTP_PORT = 2121
    }
}

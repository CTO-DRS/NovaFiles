package com.novafiles.app.core.davserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.novafiles.app.MainActivity
import com.novafiles.app.R
import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.ftp.localIpv4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** حالة مشاركة WebDAV كما تراها الواجهة */
data class DavShareState(
    val running: Boolean = false,
    val url: String? = null,
    val root: String? = null,
    val port: Int = 0,
    val error: String? = null,
)

/**
 * خدمة أمامية تستضيف خادم WebDAV طالما المشاركة مفعّلة،
 * مع إشعار دائم يعرض رابط الوصول — بنيان مرآة لـ FtpServerService.
 */
class WebDavServerService : Service() {

    companion object {
        const val ACTION_START = "com.novafiles.app.dav.ACTION_START"
        const val ACTION_STOP = "com.novafiles.app.dav.ACTION_STOP"
        const val EXTRA_ROOT = "root"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"

        private const val CHANNEL_ID = "dav_share"
        private const val NOTIF_ID = 4712

        /** سجل مشترك لأحداث خادم WebDAV — تقرأه الشاشة مباشرة */
        val connLog = ConnectionLog(200)

        private val _state = MutableStateFlow(DavShareState())
        val state: StateFlow<DavShareState> = _state.asStateFlow()

        fun start(context: Context, root: String, port: Int, user: String, pass: String) {
            val i = Intent(context, WebDavServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ROOT, root)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, WebDavServerService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private var server: WebDavServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.dav_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.dav_notif_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                server?.stop()
                server = null
                _state.value = DavShareState(running = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> {
                val root = intent?.getStringExtra(EXTRA_ROOT) ?: "/storage/emulated/0"
                val port = intent?.getIntExtra(EXTRA_PORT, 8888) ?: 8888
                val user = intent?.getStringExtra(EXTRA_USER) ?: "nova"
                val pass = intent?.getStringExtra(EXTRA_PASS) ?: "nova"
                startAsForeground()
                launchServer(root, port, user, pass)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.dav_notification_title))
            .setContentText(getString(R.string.dav_notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun launchServer(rootPath: String, port: Int, user: String, pass: String) {
        server?.stop()
        val srv = WebDavServer(
            root = File(rootPath),
            port = port,
            username = user,
            password = pass,
            log = connLog,
        )
        server = srv
        if (srv.start()) {
            val ip = localIpv4()
            _state.value = DavShareState(
                running = true,
                url = if (ip != null) "http://$ip:$port/" else null,
                root = rootPath,
                port = port,
                error = if (ip == null) getString(R.string.ftp_no_wifi) else null,
            )
        } else {
            _state.value = DavShareState(
                running = false,
                error = getString(R.string.ftp_start_failed, port),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }
}

package com.novafiles.app.core.network

import com.novafiles.app.data.db.ConnectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** مصنع RemoteFs من اتصال محفوظ — يوحّد فتح جلسات FTP وSFTP وSMB */
object RemoteFsFactory {

    fun defaultPort(protocol: String): Int = when {
        protocol.equals("SMB", true) -> 445
        protocol.equals("SFTP", true) -> 22
        protocol.equals("WEBDAV", true) -> 443
        else -> 21
    }

    /** يفتح جلسة نظام ملفات بعيد من اتصال محفوظ — قد يرمي استثناء شبكة */
    suspend fun open(c: ConnectionEntity, knownHosts: KnownHostStore? = null): RemoteFs =
        withContext(Dispatchers.IO) {
            when (c.protocol.uppercase()) {
                "SMB" -> SmbFs(
                    host = c.host,
                    port = if (c.port > 0) c.port else 445,
                    domain = c.domain,
                    username = c.username,
                    password = c.password,
                    shareName = c.share,
                )
                "SFTP" -> SftpFs(
                    host = c.host,
                    port = if (c.port > 0) c.port else 22,
                    username = c.username,
                    password = c.password,
                    knownHosts = knownHosts,
                )
                "WEBDAV" -> WebDavFs(
                    host = c.host,
                    port = if (c.port > 0) c.port else 443,
                    basePath = c.share,          // المسار الأساسي مثل /remote.php/dav/files/user
                    username = c.username,
                    password = c.password,
                    // HTTPS يُستنتج من المنفذ 443؛ يمكن إجباره بكتابة المخطط في المضيف
                    https = when {
                        c.host.startsWith("https://", true) -> true
                        c.host.startsWith("http://", true) -> false
                        else -> null
                    },
                )
                else -> FtpFs(
                    host = c.host,
                    port = if (c.port > 0) c.port else 21,
                    username = c.username,
                    password = c.password,
                )
            }
        }
}

package com.novafiles.app.core.network

import com.novafiles.app.data.db.ConnectionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** مصنع RemoteFs من اتصال محفوظ — يوحّد فتح جلسات FTP وSMB */
object RemoteFsFactory {

    fun defaultPort(protocol: String): Int = if (protocol.equals("SMB", true)) 445 else 21

    /** يفتح جلسة نظام ملفات بعيد من اتصال محفوظ — قد يرمي استثناء شبكة */
    suspend fun open(c: ConnectionEntity): RemoteFs = withContext(Dispatchers.IO) {
        when (c.protocol.uppercase()) {
            "SMB" -> SmbFs(
                host = c.host,
                port = if (c.port > 0) c.port else 445,
                domain = c.domain,
                username = c.username,
                password = c.password,
                shareName = c.share,
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

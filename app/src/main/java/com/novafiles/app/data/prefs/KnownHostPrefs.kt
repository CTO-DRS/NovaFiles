package com.novafiles.app.data.prefs

import android.content.Context
import com.novafiles.app.core.network.KnownHostStore

/**
 * مخزن بصمات مفاتيح مضيفي SFTP (نمط TOFU) في SharedPreferences خاصة بالتطبيق.
 * المفتاح «host:port» والقيمة بصمة SHA-256 بأسلوب OpenSSH.
 */
class PrefsKnownHostStore(context: Context) : KnownHostStore {

    private val prefs =
        context.getSharedPreferences("nova_known_hosts", Context.MODE_PRIVATE)

    override fun fingerprintFor(host: String, port: Int): String? =
        prefs.getString(key(host, port), null)

    override fun remember(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    private fun key(host: String, port: Int) = "${host.lowercase().trim()}:$port"
}

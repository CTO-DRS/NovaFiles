package com.novafiles.app.core.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * v0.13 — طبقة أندرويد للخزنة: مفتاح AES-256 يُولَّد داخل AndroidKeyStore
 * (غير قابل للاستخراج، مقيد بقفل الشاشة حيثما توفر) والنص المشفر يُخزَّن
 * في SharedPreferences خاصة بالتطبيق.
 *
 * هذا الملف يشير إلى أصناف android.* — لا يُحمَّل في اختبارات JVM أبداً.
 */
object KeystoreVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "novafiles_credential_key"
    private const val PREFS_NAME = "nova_vault"

    /** يفتح (أو يولّد) مفتاح الخزنة داخل Keystore */
    fun androidKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /**
     * v0.41.1 — مفتاح ختم أسرار الخوادم مع ذاكرة عملية: Keystore ثابت خلال العملية
     * فلا داعي لإعادة فتحه مع كل انبعاث Flow. يُعيد null إن فشل فتح Keystore
     * (عطل نظام نادر) — المستدعي يطبق سياسة الفشل المغلق، لا استثناءات هنا.
     */
    @Volatile
    private var cachedServerKey: SecretKey? = null

    fun serverKey(): SecretKey? {
        cachedServerKey?.let { return it }
        val key = runCatching { androidKey() }.getOrNull() ?: return null
        cachedServerKey = key
        return key
    }

    /** خزنة جاهزة للتطبيق: AES/GCM بمفتاح Keystore + تخزين SharedPreferences */
    fun open(context: Context): AesGcmVault {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = object : MutableMap<String, String> {
            private val delegate = HashMap<String, String>()

            init {
                prefs.all.forEach { (k, v) -> if (v is String) delegate[k] = v }
            }

            private fun flush() {
                prefs.edit().clear().apply { delegate.forEach { (k, v) -> putString(k, v) } }.apply()
            }

            override val size: Int get() = delegate.size
            override fun containsKey(key: String): Boolean = delegate.containsKey(key)
            override fun containsValue(value: String): Boolean = delegate.containsValue(value)
            override fun get(key: String): String? = delegate[key]
            override fun isEmpty(): Boolean = delegate.isEmpty()
            override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
                get() = delegate.entries
            override val keys: MutableSet<String> get() = delegate.keys
            override val values: MutableCollection<String> get() = delegate.values
            override fun put(key: String, value: String): String? =
                delegate.put(key, value).also { flush() }
            override fun putAll(from: Map<out String, String>) {
                delegate.putAll(from); flush()
            }
            override fun remove(key: String): String? =
                delegate.remove(key).also { flush() }
            override fun clear(): Unit {
                delegate.clear(); flush()
            }
        }
        return AesGcmVault(androidKey(), store)
    }
}

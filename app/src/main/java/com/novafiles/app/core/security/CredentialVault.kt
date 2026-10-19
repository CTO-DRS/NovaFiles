package com.novafiles.app.core.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import java.util.Base64

/**
 * v0.13 — خزنة بيانات الاعتماد: كلمات مرور الخوادم مشفرة ولا تُخزن نصاً صريحاً.
 *
 * هدف الخارطة: «بيانات الاعتماد مختومة في EncryptedSharedPreferences بمرساة
 * Keystore» — أضافت JetBrains security-crypto إلى الإهمال، لذا التنفيذ مباشر
 * وأكثر قابلية للتدقيق (بلا تراكم اعتماديات):
 *  - المفتاح AES-256 يُولَّد داخل AndroidKeyStore ولا يغادرها أبداً (KeystoreVault)
 *  - التشفير AES/GCM بـ IV عشوائي 12 بايت لكل عملية (لا تكرار IV أبداً)
 *  - التخزين: نص «NFCV1:<iv b64>:<cipher b64>» في مكان محقون (SharedPreferences)
 *
 * النواة خالصة JVM — تُختبر بمفتاح عادي، وطبقة أندرويد توفر المفتاح من Keystore.
 */

/** واجهة الخزنة — الذاكرة للخوادم والاختبارات، وKeystore للأجهزة */
interface CredentialVault : AutoCloseable {
    /** يحفظ سراً (كلمة مرور/توكن) بمعرف مستقر مثل conn-7-password */
    fun save(id: String, secret: String)

    /** يقرأ سراً محفوظاً — null إن لم يوجد أو فسد تشفيره */
    fun load(id: String): String?

    /** يحذف سراً */
    fun delete(id: String)

    /** كل المعرفات المحفوظة */
    fun ids(): Set<String>
}

/** خزنة AES/GCM فوق مخزن نصي محقون (SharedPreferences أو Map اختبار) */
class AesGcmVault(
    private val key: SecretKey,
    private val store: MutableMap<String, String>,
) : CredentialVault {

    override fun save(id: String, secret: String) {
        if (secret.isEmpty()) {
            store.remove(id)
            return
        }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val dataB64 = Base64.getEncoder().encodeToString(encrypted)
        store[id] = "$MAGIC:$ivB64:$dataB64"
    }

    override fun load(id: String): String? {
        val raw = store[id] ?: return null
        val parts = raw.split(':')
        if (parts.size != 3 || parts[0] != MAGIC) return null
        return runCatching {
            val iv = Base64.getDecoder().decode(parts[1])
            val data = Base64.getDecoder().decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull() // بصمة GCM لا تمر — السر فاسد أو المفتاح تغير → null آمن
    }

    override fun delete(id: String) {
        store.remove(id)
    }

    override fun ids(): Set<String> = store.keys.toSet()

    override fun close() { /* لا موارد */ }

    companion object {
        private const val MAGIC = "NFCV1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}

/** خزنة ذاكرة للاختبارات — لا شيفرات، الشكل موحّد مع الواجهة */
class InMemoryVault : CredentialVault {
    private val map = HashMap<String, String>()

    override fun save(id: String, secret: String) {
        if (secret.isEmpty()) map.remove(id) else map[id] = secret
    }

    override fun load(id: String): String? = map[id]

    override fun delete(id: String) {
        map.remove(id)
    }

    override fun ids(): Set<String> = map.keys.toSet()

    override fun close() { /* لا موارد */ }
}

/** مساعد مفاتيح للاختبار — مفتاح AES-256 ثابت التوليد على JVM */
object VaultKeys {
    fun jvmTestKey(seed: ByteArray = ByteArray(32)): SecretKey =
        SecretKeySpec(seed, "AES")
}

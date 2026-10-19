package com.novafiles.app.core.vault2

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.novafiles.app.data.prefs.SettingsStore
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — مدير الخزنة 2.0 (طبقة أندرويد): يربط النواة الخالصة بالجهاز.
 *
 * المفتاح الجذر: 32 بايت عشوائية تُغلَّف بمفتاح AES داخل AndroidKeyStore
 * (غير قابل للتصدير) وتُخزَّن مغلفة في مفضلات خاصة — نمط «Keystore root +
 * wrapped key» القياسي الذي يجعل الاسترجاع ممكناً والعزل حقيقياً.
 *
 * ملاحظات:
 *  - الاشتقاقات (HKDF) والتشفير كله في النواة الخالصة — هنا فقط Keystore والملفات.
 *  - وضع التنكّر يبدّل activity-alias لأيقونة الحاسبة عبر PackageManager.
 */
class Vault2Manager(
    private val context: Context,
    private val settings: SettingsStore,
) {

    private val random = SecureRandom()

    // ---------- المفتاح الجذر ----------

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** المفتاح الجذر 32B — يُنشأ ويُغلَّف أول مرة، ثم يُفتح من التغليف */
    fun masterKey(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        if (wrapped != null) {
            val parts = wrapped.split(':')
            if (parts.size == 2) {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val data = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv))
                return cipher.doFinal(data)
            }
        }
        // توليد أولي
        val master = ByteArray(32).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(master)
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
        return master
    }

    /** هل الخزنة مهيأة (مفتاح جذر + فهرس موجود)؟ */
    fun isInitialized(): Boolean = vaultDir().let { File(it, "index.bin") }.exists()

    // ---------- التخزين ----------

    /** مجلد الخزنة على التخزين الأولي (بطاقة SD عبر SAF تجيء في إعداد لاحق) */
    private fun vaultDir(): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Vault2")
        dir.mkdirs()
        return dir
    }

    fun openRepository(): Vault2Repository = Vault2Repository(LocalFileFs(vaultDir()), masterKey())

    /**
     * الخزنة الوهمية لوضع التنكّر: مستودع بكر بمجلد منفصل ومفتاح عشوائي مستقل —
     * يبدو حقيقياً (قفل وفك وإضافة ملفات) لكن لا علاقة له بالخزنة الفعلية.
     */
    fun openDecoyRepository(): Vault2Repository {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_DECOY_MASTER, null)
        val decoyMaster = if (stored != null) {
            Base64.decode(stored, Base64.NO_WRAP)
        } else {
            val fresh = ByteArray(32).also { random.nextBytes(it) }
            prefs.edit().putString(KEY_DECOY_MASTER, Base64.encodeToString(fresh, Base64.NO_WRAP)).apply()
            fresh
        }
        val dir = File(vaultDir().parentFile, "Vault2Decoy")
        dir.mkdirs()
        return Vault2Repository(LocalFileFs(dir), decoyMaster)
    }

    fun openIntrusionLog(fs: VaultFs): IntrusionLog = IntrusionLog(fs, masterKey())

    // ---------- PIN وسياسة التنكّر ----------

    private val hasher = PinHasher()

    suspend fun decoyConfig(): DecoyConfig? {
        val store = settings.peekRaw()
        val real = store[stringPreferencesKeySafe(KEY_REAL_HASH)] ?: return null
        val decoy = store[stringPreferencesKeySafe(KEY_DECOY_HASH)] ?: ""
        val salt = store[stringPreferencesKeySafe(KEY_PIN_SALT)] ?: return null
        return DecoyConfig(real, decoy, salt)
    }

    suspend fun isPinSet(): Boolean = decoyConfig() != null

    /** إعداد الرمز الحقيقي — إلزامي قبل أول استخدام (معيار الخارطة) */
    suspend fun setRealPin(pin: String) {
        val cfg = decoyConfig()
        val salt = cfg?.salt ?: hasher.newSalt()
        settings.putRaw(KEY_REAL_HASH, hasher.hash(pin, salt))
        settings.putRaw(KEY_PIN_SALT, salt)
    }

    /** رمز التنكّر (يفتح خزنة وهمية) — اختياري */
    suspend fun setDecoyPin(pin: String) {
        val cfg = decoyConfig() ?: error("set real pin first")
        settings.putRaw(KEY_DECOY_HASH, hasher.hash(pin, cfg.salt))
    }

    suspend fun policy(): DecoyPolicy? = decoyConfig()?.let { DecoyPolicy(it, hasher) }

    /** تقييم محاولة دخول + قيد التسلل عند الفشل */
    suspend fun evaluateEntry(pin: String, log: IntrusionLog?, photoEnabled: Boolean): DecoyOutcome {
        val outcome = policy()?.evaluate(pin) ?: return DecoyOutcome.Fail(1)
        if (outcome is DecoyOutcome.Fail && log != null) {
            log.record(
                IntrusionLog.Kind.WRONG_PIN,
                "attempt #$outcome",
                if (photoEnabled) runCatching { IntrusionCapturer.capture(context) }.getOrNull() else null,
            )
        }
        return outcome
    }

    suspend fun clearAllSecrets() {
        settings.putRaw(KEY_REAL_HASH, "")
        settings.putRaw(KEY_DECOY_HASH, "")
        settings.putRaw(KEY_PIN_SALT, "")
    }

    // ---------- وضع التنكّر ----------

    /** تبديل أيقونة القاذف: حاسبة (تنكّر) أو أيقونة NovaFiles العادية */
    fun setDecoyLauncherEnabled(enabled: Boolean) {
        val pm = context.packageManager
        val calc = ComponentName(context, "com.novafiles.app.LauncherCalc")
        val main = ComponentName(context, "com.novafiles.app.MainActivity")
        pm.setComponentEnabledSetting(
            calc,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        pm.setComponentEnabledSetting(
            main,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    suspend fun isDecoyEnabled(): Boolean =
        settings.peekRaw()[stringPreferencesKeySafe(KEY_DECOY_MODE)] == "1"

    suspend fun setDecoyEnabled(enabled: Boolean) {
        settings.putRaw(KEY_DECOY_MODE, if (enabled) "1" else "0")
        setDecoyLauncherEnabled(enabled)
    }

    // ---------- الإعدادات الأخرى ----------

    suspend fun setAutoHideEnabled(enabled: Boolean) =
        settings.putRaw(KEY_AUTO_HIDE, if (enabled) "1" else "0")

    suspend fun isAutoHideEnabled(): Boolean =
        settings.peekRaw()[stringPreferencesKeySafe(KEY_AUTO_HIDE)] != "0" // افتراضياً مفعّل

    suspend fun setIntrusionPhotoEnabled(enabled: Boolean) =
        settings.putRaw(KEY_INTRUSION_PHOTO, if (enabled) "1" else "0")

    suspend fun isIntrusionPhotoEnabled(): Boolean =
        settings.peekRaw()[stringPreferencesKeySafe(KEY_INTRUSION_PHOTO)] == "1"

    // ---------- النسخ الاحتياطي ----------

    suspend fun newRecoveryPhrase(): List<String> = RecoveryPhrase.generate(random)

    /** بناء ملف نسخة احتياطية من مستودع حي */
    fun buildBackup(repository: Vault2Repository, words: List<String>): ByteArray =
        RecoveryBackup.create(words, repository.exportMasterKey(), repository.index.serialize())

    /** تبني مفتاحاً مستعاداً من نسخة احتياطية — يعاد تغليفه بـ Keystore */
    fun adoptRestoredMaster(masterBytes: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val encrypted = cipher.doFinal(masterBytes)
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    /** جذر مجلد الخزنة (للمسح الآمن الكامل) */
    fun vaultRoot(): File = vaultDir()

    private fun stringPreferencesKeySafe(name: String) = androidx.datastore.preferences.core.stringPreferencesKey(name)

    companion object {
        private const val PREFS_NAME = "nova_vault2"
        private const val KS_ALIAS = "nova_vault2_root"
        private const val KEY_WRAPPED = "wrapped_master"
        private const val KEY_DECOY_MASTER = "decoy_master"
        const val KEY_REAL_HASH = "vault2_real_hash"
        const val KEY_DECOY_HASH = "vault2_decoy_hash"
        const val KEY_PIN_SALT = "vault2_pin_salt"
        const val KEY_DECOY_MODE = "vault2_decoy_mode"
        const val KEY_AUTO_HIDE = "vault2_auto_hide"
        const val KEY_INTRUSION_PHOTO = "vault2_intrusion_photo"
    }
}

/** مفتاح AES من بايتات خام (للاختبارات والطبقات العليا) */
fun ByteArray.toAesKey(): SecretKey = SecretKeySpec(this, "AES")

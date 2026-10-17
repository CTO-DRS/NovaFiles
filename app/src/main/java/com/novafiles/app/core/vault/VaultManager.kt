package com.novafiles.app.core.vault

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novafiles.app.data.prefs.SettingsStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * الخزنة المشفرة (v0.3 MVP):
 * - تشفير AES-256-GCM بمفتاح محمي داخل Android Keystore (غير قابل للتصدير)
 * - كل ملف يُخزن بصيغة .nvf: [توقيع 4 بايت][IV 12 بايت][البيانات المشفرة]
 * - رمز PIN يُخزن كتجزئة SHA-256 مملحة (البصمة تُضاف في v0.3.1)
 */
class VaultManager(context: Context, private val settings: SettingsStore) {

    companion object {
        private const val KS_ALIAS = "nova_vault_key"
        private const val MAGIC = "NVF1"
        private const val GCM_TAG_BITS = 128
        const val SALT_KEY = "vault_salt"
        const val PIN_KEY = "vault_pin_hash"
    }

    val vaultDir: File = File(Environment.getExternalStorageDirectory(), ".NovaFilesVault")

    // ---------- المفتاح ----------

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    // ---------- PIN ----------

    private suspend fun currentSalt(): String {
        val store = settings.peekRaw()
        return store[stringPreferencesKey(SALT_KEY)] ?: run {
            val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            settings.putRaw(SALT_KEY, s)
            s
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    suspend fun isPinSet(): Boolean =
        !settings.peekRaw()[stringPreferencesKey(PIN_KEY)].isNullOrBlank()

    suspend fun setPin(pin: String) {
        val salt = currentSalt()
        settings.putRaw(PIN_KEY, sha256(salt + pin))
    }

    suspend fun verifyPin(pin: String): Boolean {
        val salt = currentSalt()
        return settings.peekRaw()[stringPreferencesKey(PIN_KEY)] == sha256(salt + pin)
    }

    suspend fun clearPin() {
        settings.putRaw(PIN_KEY, "")
    }

    // ---------- التشفير ----------

    /** تشفير ملف ونقله إلى الخزنة، ثم حذف الأصل */
    suspend fun encryptIntoVault(src: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            vaultDir.mkdirs()
            require(src.exists() && src.isFile) { "source missing" }
            val dst = File(vaultDir, "${src.name}.nvf")
            var out = dst
            var i = 1
            while (out.exists()) {
                out = File(vaultDir, "${src.nameWithoutExtension} ($i).nvf")
                i++
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(src.readBytes())
            out.writeBytes(MAGIC.toByteArray() + iv + encrypted)
            src.delete()
            out
        }
    }

    /** فك تشفير ملف من الخزنة إلى مسار هدف */
    suspend fun decryptToFile(encrypted: File, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(encrypted.exists()) { "vault file missing" }
            val raw = encrypted.readBytes()
            require(raw.size > MAGIC.length + 12) { "corrupted vault file" }
            check(String(raw, 0, 4, Charsets.US_ASCII) == MAGIC) { "not a vault file" }
            val iv = raw.copyOfRange(4, 16)
            val data = raw.copyOfRange(16, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(data)
            val dst = if (destination.isDirectory) File(destination, encrypted.nameWithoutExtension)
            else destination
            dst.writeBytes(plain)
            dst
        }
    }

    fun listEncrypted(): List<File> =
        vaultDir.listFiles()?.filter { it.isFile && it.name.endsWith(".nvf") }?.sortedBy { it.name }
            ?: emptyList()

    fun deleteEncrypted(file: File): Boolean = file.delete()
}

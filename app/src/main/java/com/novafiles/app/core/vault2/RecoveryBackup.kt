package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — النسخة الاحتياطية للاسترجاع: ملف واحد يحتوي المفتاح الرئيسي مغلّفاً
 * بمفتاح مشتق من عبارة الاسترجاع + فهرس الخزنة المشفر.
 *
 * الصيغة NVBR1:
 *   [ magic "NVBR1" 5B ][ PBKDF2 iterations 4B BE ][ ملح العبارة 16B ][ IV 12B ]
 *   [ نص مشفر GCM = masterKey(32B) || indexBytes ]
 *
 * تدريب الاسترجاع (معيار النجاح): إنشاء → نسخ احتياطي → خزنة جديدة كلياً
 * (مفتاح جذر مختلف) → استيراد → فك الملفات بنجاح.
 */
object RecoveryBackup {

    const val MAGIC = "NVBR1"
    const val SALT_LEN = 16
    const val IV_LEN = 12
    const val MASTER_LEN = 32

    class CorruptedBackup(message: String) : IllegalStateException(message)

    /** يبني ملف النسخ الاحتياطي من العبارة والمفتاح الجذر والفهرس */
    fun create(recoveryWords: List<String>, masterKey: ByteArray, indexBytes: ByteArray): ByteArray {
        require(masterKey.size == MASTER_LEN) { "master key must be 32 bytes" }
        require(RecoveryPhrase.validate(recoveryWords)) { "invalid recovery phrase" }
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val iterations = RecoveryPhrase.PBKDF2_ITERATIONS
        val wrapKey = RecoveryPhrase.deriveKey(recoveryWords, salt, iterations)
        val payload = masterKey + indexBytes
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload)

        val out = ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.writeBE32(iterations)
        out.write(salt)
        out.write(iv)
        out.write(encrypted)
        return out.toByteArray()
    }

    /** نتيجة الاستيراد: المفتاح الرئيسي المستعاد + فهرس الخزنة */
    data class Restored(val masterKey: ByteArray, val index: VaultIndex)

    /** يفتح نسخة احتياطية بعبارة صحيحة — يفشل بأمان على عبارة خاطئة أو ملف تالف */
    fun restore(backup: ByteArray, recoveryWords: List<String>): Restored {
        if (backup.size < 5 + 4 + SALT_LEN + IV_LEN + MASTER_LEN + 16) {
            throw CorruptedBackup("backup too small")
        }
        if (String(backup, 0, 5, Charsets.US_ASCII) != MAGIC) throw CorruptedBackup("not a backup file")
        val iterations = ((backup[5].toInt() and 0xFF) shl 24) or ((backup[6].toInt() and 0xFF) shl 16) or
            ((backup[7].toInt() and 0xFF) shl 8) or (backup[8].toInt() and 0xFF)
        if (iterations !in 10_000..2_000_000) throw CorruptedBackup("bad iterations")
        var off = 9
        val salt = backup.copyOfRange(off, off + SALT_LEN); off += SALT_LEN
        val iv = backup.copyOfRange(off, off + IV_LEN); off += IV_LEN
        val encrypted = backup.copyOfRange(off, backup.size)

        val wrapKey = RecoveryPhrase.deriveKey(recoveryWords, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrapKey, "AES"), GCMParameterSpec(128, iv))
        val payload = try {
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            throw CorruptedBackup("wrong recovery phrase or corrupted backup")
        }
        require(payload.size > MASTER_LEN) { "payload truncated" }
        val masterKey = payload.copyOfRange(0, MASTER_LEN)
        val index = VaultIndex.deserialize(payload.copyOfRange(MASTER_LEN, payload.size))
        return Restored(masterKey, index)
    }

    /** تدريب استرجاع كامل على مستودع: يبني نسخة من مستودع حي ويستعيدها في مستودع بكر */
    fun drill(source: Vault2Repository, words: List<String>, targetFs: VaultFs): Vault2Repository {
        val backup = create(words, source.exportMasterKey(), source.index.serialize())
        val restored = restore(backup, words)
        val fresh = Vault2Repository(targetFs, restored.masterKey)
        // استبدال الفهرس الجديد (فارغ) بالمستعاد — ثم إعادة حفظه بمفتاح الجذر المستعاد
        fresh.replaceIndex(restored.index)
        return fresh
    }

    private fun ByteArrayOutputStream.writeBE32(v: Int) {
        write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
    }
}

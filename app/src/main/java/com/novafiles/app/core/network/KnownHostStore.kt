package com.novafiles.app.core.network

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * مخزن مفاتيح المضيفين المعروفين لنمط «الثقة بأول استخدام» (TOFU) لعميل SFTP.
 *
 * عند أول اتصال بخادم جديد تُحفظ بصمة مفتاحه العام، وعند كل اتصال لاحق يجب أن
 * تطابق البصمة المخزّنة — إن اختلفت يُرفض الاتصال فوراً (حماية من هجمات
 * الوسيط MITM وتحذير من خوادم انتحالية تحمل عنوان نفس الخادم).
 */
interface KnownHostStore {
    /** بصمة المفتاح المحفوظة سابقاً لمضيف، أو null إن لم يُرَ من قبل */
    fun fingerprintFor(host: String, port: Int): String?

    /** حفظ/تحديث بصمة مفتاح مضيف (تُستدعى عند أول اتصال ناجح) */
    fun remember(host: String, port: Int, fingerprint: String)
}

/** مخزن بالذاكرة فقط — يُستخدم في اختبارات الوحدة */
class InMemoryKnownHosts : KnownHostStore {
    private val map = HashMap<String, String>()
    override fun fingerprintFor(host: String, port: Int): String? = map["$host:$port"]
    override fun remember(host: String, port: Int, fingerprint: String) {
        map["$host:$port"] = fingerprint
    }
}

/** حساب بصمات المفاتيح العامة بأسلوب OpenSSH (SHA-256، base64 بلا حشو) */
object HostFingerprints {
    fun sha256(key: PublicKey): String = Base64.getEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(key.encoded),
    )
}

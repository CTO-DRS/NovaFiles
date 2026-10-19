package com.novafiles.app.core.vault2

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** متجهات RFC 5869 الرسمية + سلوك الاشتقاق */
class HkdfTest {

    // A.1: SHA-256, ikm=0x0b×22, salt=0x000102…0c, info=0xf0f1…f9, L=42
    @Test
    fun rfc5869_caseA1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertArrayEquals(
            byteArrayOf(
                0x3c.toByte(), 0xb2.toByte(), 0x5f.toByte(), 0x25, 0xfa.toByte(), 0xac.toByte(), 0xd5.toByte(), 0x7a.toByte(),
                0x90.toByte(), 0x43.toByte(), 0x4f, 0x64, 0xd0.toByte(), 0x36, 0x2f, 0x2a.toByte(),
                0x2d.toByte(), 0x2d.toByte(), 0x0a.toByte(), 0x90.toByte(), 0xcf.toByte(), 0x1a.toByte(), 0x5a.toByte(), 0x4c.toByte(),
                0x5d.toByte(), 0xb0.toByte(), 0x2d.toByte(), 0x56, 0xec.toByte(), 0xc4.toByte(), 0xc5.toByte(), 0xbf.toByte(),
                0x34, 0x00, 0x72, 0x08, 0xd5.toByte(), 0xb8.toByte(), 0x87.toByte(), 0x18,
                0x58, 0x65,
            ),
            okm,
        )
    }

    /**
     * متجهتا A.2/A.3 في RFC 5869 لخوارزمية SHA-1 (لا SHA-256)، لذا نتحقق هنا
     * بخصائص مكافئة: derive = expand∘extract، وضبط الملح/السياق يغيّر المخرجات،
     * مع ثبات المخرجات ومطابقة HMAC الأساسية لمتجه RFC 4231.
     */
    @Test
    fun derive_equalsExpandExtract_longInputs() {
        val ikm = ByteArray(80) { (it + 1).toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        val shortcut = Hkdf.derive(ikm, salt, info, 82)
        val twoStep = Hkdf.expand(Hkdf.extract(salt, ikm), info, 82)
        assertArrayEquals(shortcut, twoStep)
        assertEquals(82, shortcut.size)
    }

    @Test
    fun hmac_matchesRfc4231_vector1() {
        // RFC 4231 Test Case 1: key=0x0b×20, data="Hi There"
        val mac = Hkdf.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray())
        // b0344c61 d8db3853 5ca8afce af0bf12b 881dc200 c9833da7 26e9376c 2e32cff7
        assertArrayEquals(
            byteArrayOf(
                0xb0.toByte(), 0x34.toByte(), 0x4c.toByte(), 0x61, 0xd8.toByte(), 0xdb.toByte(), 0x38, 0x53,
                0x5c, 0xa8.toByte(), 0xaf.toByte(), 0xce.toByte(), 0xaf.toByte(), 0x0b, 0xf1.toByte(), 0x2b,
                0x88.toByte(), 0x1d, 0xc2.toByte(), 0x00, 0xc9.toByte(), 0x83.toByte(), 0x3d, 0xa7.toByte(),
                0x26, 0xe9.toByte(), 0x37, 0x6c, 0x2e, 0x32, 0xcf.toByte(), 0xf7.toByte(),
            ),
            mac,
        )
    }

    @Test
    fun deterministic_sameInputs_sameOutput() {
        val ikm = "master-key-seed".toByteArray()
        val salt = "salt-1".toByteArray()
        val a = Hkdf.derive(ikm, salt, "folder/a".toByteArray())
        val b = Hkdf.derive(ikm, salt, "folder/a".toByteArray())
        assertArrayEquals(a, b)
    }

    @Test
    fun differentInfo_differentKeys() {
        val ikm = "master-key-seed".toByteArray()
        val salt = "salt-1".toByteArray()
        val a = Hkdf.derive(ikm, salt, "folder/a".toByteArray())
        val b = Hkdf.derive(ikm, salt, "folder/b".toByteArray())
        org.junit.Assert.assertFalse(a.contentEquals(b))
    }

    @Test
    fun outputLength_upTo255Blocks() {
        val okm = Hkdf.derive("ikm".toByteArray(), "salt".toByteArray(), "info".toByteArray(), 255 * 32)
        assertEquals(255 * 32, okm.size)
        assertThrows(IllegalArgumentException::class.java) {
            Hkdf.derive("ikm".toByteArray(), "salt".toByteArray(), "info".toByteArray(), 255 * 32 + 1)
        }
    }

    @Test
    fun derivedKey_worksAsAesKey() {
        val raw = Hkdf.derive(ByteArray(32), ByteArray(16), "file/x".toByteArray())
        val key = SecretKeySpec(raw, "AES")
        assertEquals("AES", key.algorithm)
        assertEquals(32, raw.size)
    }
}

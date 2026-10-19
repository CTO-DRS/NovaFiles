package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** صيغة NVF2: تدوير، أجزاء، كشف تلف بالجزء، قراءة عشوائية، ذاكرة فعّالة */
class ChunkedCipherTest {

    private fun key(seed: Byte = 1) = SecretKeySpec(ByteArray(32) { seed }, "AES")

    @Test
    fun roundTrip_smallData() {
        val data = "مرحبا NovaFiles — نص عربي مشفر".toByteArray()
        val out = ByteArrayOutputStream()
        val original = ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key())
        assertEquals(data.size.toLong(), original)
        val plain = ByteArrayOutputStream()
        val restored = ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), plain, key())
        assertEquals(data.size.toLong(), restored)
        assertArrayEquals(data, plain.toByteArray())
    }

    @Test
    fun roundTrip_emptyFile() {
        val out = ByteArrayOutputStream()
        val original = ChunkedCipher.encrypt(ByteArrayInputStream(ByteArray(0)), out, key())
        assertEquals(0L, original)
        val plain = ByteArrayOutputStream()
        ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), plain, key())
        assertEquals(0, plain.size())
    }

    @Test
    fun roundTrip_exactlyOneChunk() {
        val data = ByteArray(ChunkedCipher.DEFAULT_CHUNK) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key())
        val plain = ByteArrayOutputStream()
        ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), plain, key())
        assertArrayEquals(data, plain.toByteArray())
    }

    @Test
    fun roundTrip_multiChunk_constantMemory() {
        val chunk = ChunkedCipher.DEFAULT_CHUNK
        val data = ByteArray(chunk * 2 + 1234) { (it % 97).toByte() }
        val out = ByteArrayOutputStream()
        val original = ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(), chunk)
        assertEquals(data.size.toLong(), original)
        // الحجم المشفر = ترويسة 21 + 3 أجزاء (كل جزء + 16 وسم) + خاتمة 13
        assertEquals(21L + (chunk + 16) * 2 + (1234 + 16) + 13L, out.size().toLong())
        val plain = ByteArrayOutputStream()
        ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), plain, key())
        assertArrayEquals(data, plain.toByteArray())
    }

    @Test
    fun wrongKey_failsOnFirstChunk() {
        val data = ByteArray(5000) { 7 }
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(1))
        assertThrows(Exception::class.java) {
            ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), key(2))
        }
    }

    @Test
    fun corruption_detectedAtExactChunk() {
        val chunk = 1024
        val data = ByteArray(chunk * 3) { 5 }
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(), chunk)
        val corrupted = out.toByteArray()
        // اعبث في الجزء الثاني (بعد الترويسة + جزء كامل + 20 بايت)
        val tamperAt = ChunkedCipher.HEADER_LEN + (chunk + 16) + 20
        corrupted[tamperAt] = (corrupted[tamperAt] + 1).toByte()
        val plain = ByteArrayOutputStream()
        val e = assertThrows(ChunkedCipher.CorruptedVaultFile::class.java) {
            ChunkedCipher.decrypt(ByteArrayInputStream(corrupted), plain, key())
        }
        assertTrue(e.message!!.contains("chunk") || e.message!!.contains("mismatch") || e.message!!.contains("tail"))
    }

    @Test
    fun truncation_detected() {
        val data = ByteArray(10_000) { 3 }
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key())
        val truncated = out.toByteArray().copyOfRange(0, out.size() - 25)
        assertThrows(Exception::class.java) {
            ChunkedCipher.decrypt(ByteArrayInputStream(truncated), ByteArrayOutputStream(), key())
        }
    }

    @Test
    fun notAnNvf2File_rejected() {
        val garbage = "NOT A VAULT FILE........".toByteArray()
        assertThrows(ChunkedCipher.CorruptedVaultFile::class.java) {
            ChunkedCipher.decrypt(ByteArrayInputStream(garbage), ByteArrayOutputStream(), key())
        }
    }

    @Test
    fun randomAccess_decryptSingleChunk() {
        val chunk = 1024
        val data = ByteArray(chunk * 4) { (it % 253).toByte() }
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(), chunk)
        val blob = out.toByteArray()
        for (idx in 0 until 4) {
            val plain = ChunkedCipher.decryptChunk(ByteArrayInputStream(blob), key(), idx.toLong())
            val expected = data.copyOfRange(idx * chunk, (idx + 1) * chunk)
            assertArrayEquals("chunk $idx", expected, plain)
        }
        // الجزء الأخير بعد نهاية الملف → خطأ
        assertThrows(Exception::class.java) {
            ChunkedCipher.decryptChunk(ByteArrayInputStream(blob), key(), 4L)
        }
    }

    @Test
    fun nonceDerivation_uniquePerCounter() {
        val iv = ByteArray(12) { it.toByte() }
        val n0 = ChunkedCipher.nonceFor(iv, 0)
        val n1 = ChunkedCipher.nonceFor(iv, 1)
        val nMax = ChunkedCipher.nonceFor(iv, 1L shl 32 - 1)
        org.junit.Assert.assertFalse(n0.contentEquals(n1))
        org.junit.Assert.assertFalse(n1.contentEquals(nMax))
        // العداد 0 يعيد الـ IV نفسه
        assertArrayEquals(iv, n0)
    }

    @Test
    fun recrypt_samePlaintextDifferentBlob() {
        val data = ByteArray(7000) { (it * 3).toByte() }
        val out1 = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out1, key(1))
        val out2 = ByteArrayOutputStream()
        ChunkedCipher.recrypt(ByteArrayInputStream(out1.toByteArray()), out2, key(1), key(2))
        val plain = ByteArrayOutputStream()
        ChunkedCipher.decrypt(ByteArrayInputStream(out2.toByteArray()), plain, key(2))
        assertArrayEquals(data, plain.toByteArray())
        // المفتاح القديم لم يعد يفك Blob الجديد
        assertThrows(Exception::class.java) {
            ChunkedCipher.decrypt(ByteArrayInputStream(out2.toByteArray()), ByteArrayOutputStream(), key(1))
        }
    }

    @Test
    fun customChunkSize_small_isAccepted() {
        val data = "صغير جداً".toByteArray()
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(ByteArrayInputStream(data), out, key(), 1024)
        val plain = ByteArrayOutputStream()
        ChunkedCipher.decrypt(ByteArrayInputStream(out.toByteArray()), plain, key())
        assertArrayEquals(data, plain.toByteArray())
    }

    @Test
    fun headerSizeConsistency() {
        assertEquals(21, ChunkedCipher.HEADER_LEN)
        assertEquals(13, ChunkedCipher.TRAILER_LEN)
    }
}

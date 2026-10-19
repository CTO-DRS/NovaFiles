package com.novafiles.app.core.vault2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** عبارة BIP39: المتجه الرسمي، التحقق، الاشتقاق */
class RecoveryPhraseTest {

    /** المتجه الرسمي BIP39: إنتروبيا أصفار → 12 كلمة معروفة */
    @Test
    fun bip39_officialVector() {
        val words = RecoveryPhrase.fromEntropy(ByteArray(16))
        assertEquals(
            listOf(
                "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
                "abandon", "abandon", "abandon", "abandon", "abandon", "about",
            ),
            words,
        )
        assertTrue(RecoveryPhrase.validate(words))
    }

    @Test
    fun generate_12ValidWords() {
        repeat(20) {
            val words = RecoveryPhrase.generate()
            assertEquals(12, words.size)
            assertTrue(RecoveryPhrase.validate(words))
            assertTrue(words.all { it in com.novafiles.app.core.vault2.Bip39Wordlist.WORDS })
        }
    }

    @Test
    fun validate_rejectsChecksumFlip() {
        val words = RecoveryPhrase.generate()
        // غيّر كلمة واحدة لكلمة صالحة أخرى — checksum يكشف
        val flipped = words.mapIndexed { i, w -> if (i == 5) "zebra" else w }
        assertFalse(RecoveryPhrase.validate(flipped))
    }

    @Test
    fun validate_rejectsWrongLengthAndUnknownWords() {
        assertFalse(RecoveryPhrase.validate(emptyList()))
        assertFalse(RecoveryPhrase.validate(RecoveryPhrase.generate().take(11)))
        assertFalse(RecoveryPhrase.validate(List(12) { "notinlist" }))
    }

    @Test
    fun validate_caseAndWhitespaceTolerant() {
        val words = RecoveryPhrase.generate()
        assertTrue(RecoveryPhrase.validate(words.map { " ${it.uppercase()} " }))
    }

    @Test
    fun deriveKey_stableAndPhraseBound() {
        val words = RecoveryPhrase.generate()
        val salt = ByteArray(16) { 3 }
        val k1 = RecoveryPhrase.deriveKey(words, salt)
        val k2 = RecoveryPhrase.deriveKey(words, salt)
        assertArrayEquals(k1, k2)
        assertEquals(32, k1.size)
        // عبارة مختلفة → مفتاح مختلف
        val other = RecoveryPhrase.generate()
        assertFalse(k1.contentEquals(RecoveryPhrase.deriveKey(other, salt)))
    }

    @Test
    fun deriveKey_rejectsInvalidPhrase() {
        val salt = ByteArray(16) { 1 }
        val e = runCatching { RecoveryPhrase.deriveKey(List(12) { "abandon" }, salt) }
        assertFalse(e.isSuccess) // checksum أصفار ≠ عن «about» الأخيرة
    }
}

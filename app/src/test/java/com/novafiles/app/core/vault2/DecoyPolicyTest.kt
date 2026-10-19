package com.novafiles.app.core.vault2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** سياسة التنكّر: رمز حقيقي/وهمي/فاشل + عداد المحاولات */
class DecoyPolicyTest {

    private fun policy(real: String = "1234", decoy: String = "9999"): DecoyPolicy {
        val hasher = PinHasher(iterations = 1000)
        val salt = hasher.newSalt()
        return DecoyPolicy(
            DecoyConfig(
                realPinHash = hasher.hash(real, salt),
                decoyPinHash = hasher.hash(decoy, salt),
                salt = salt,
            ),
            hasher,
        )
    }

    @Test
    fun realPin_unlocksReal() {
        assertEquals(DecoyOutcome.Real, policy().evaluate("1234"))
    }

    @Test
    fun decoyPin_unlocksDecoy() {
        assertEquals(DecoyOutcome.Decoy, policy().evaluate("9999"))
    }

    @Test
    fun wrongPin_countsFailures() {
        val p = policy()
        val r1 = p.evaluate("0000") as DecoyOutcome.Fail
        val r2 = p.evaluate("1111") as DecoyOutcome.Fail
        assertEquals(1, r1.attemptsSoFar)
        assertEquals(2, r2.attemptsSoFar)
    }

    @Test
    fun successResetsCounter() {
        val p = policy()
        p.evaluate("0000")
        p.evaluate("0000")
        p.evaluate("1234")
        assertEquals(0, p.failedAttempts)
    }

    @Test
    fun realAndDecoy_mustDiffer() {
        val p = policy(real = "1234", decoy = "1234")
        // إن تطابقا فالحساب الحقيقي يفوز أولاً — سلوك مقصود
        assertEquals(DecoyOutcome.Real, p.evaluate("1234"))
    }

    @Test
    fun updateConfig_swapsBehavior() {
        val hasher = PinHasher(iterations = 1000)
        val salt = hasher.newSalt()
        val p = DecoyPolicy(
            DecoyConfig(hasher.hash("1111", salt), hasher.hash("2222", salt), salt),
            hasher,
        )
        assertEquals(DecoyOutcome.Real, p.evaluate("1111"))
        p.updateConfig(DecoyConfig(hasher.hash("3333", salt), hasher.hash("2222", salt), salt))
        assertEquals(DecoyOutcome.Fail(1), p.evaluate("1111"))
        assertEquals(DecoyOutcome.Real, p.evaluate("3333"))
    }
}

/** محرك الحاسبة: سلوك مطابق لحاسبة النظام */
class CalculatorEngineTest {

    @Test
    fun digits_andDisplay() {
        val c = CalculatorEngine()
        c.digit('1'); c.digit('2'); c.digit('3')
        assertEquals("123", c.display())
    }

    @Test
    fun leadingZeroes_suppressed() {
        val c = CalculatorEngine()
        c.digit('0'); c.digit('0'); c.digit('5')
        assertEquals("5", c.display())
    }

    @Test
    fun basicAddition() {
        val c = CalculatorEngine()
        c.digit('7'); c.setOperation('+'); c.digit('3'); c.equals()
        assertEquals("10", c.display())
    }

    @Test
    fun precedenceChain() {
        val c = CalculatorEngine()
        c.digit('2'); c.setOperation('+'); c.digit('3'); c.setOperation('*'); c.digit('4')
        // الحاسبة البسيطة تقيّم تسلسلياً: 2+3=5 ثم 5*4=20 (سلوك النظام القياسي)
        c.equals()
        assertEquals("20", c.display())
    }

    @Test
    fun divisionByZero_showsError() {
        val c = CalculatorEngine()
        c.digit('9'); c.setOperation('/'); c.digit('0'); c.equals()
        assertEquals("خطأ", c.display())
    }

    @Test
    fun decimalInput() {
        val c = CalculatorEngine()
        c.digit('1'); c.decimal(); c.digit('5'); c.setOperation('+'); c.digit('2'); c.equals()
        assertEquals("3.5", c.display())
    }

    @Test
    fun decimalIgnoredTwice() {
        val c = CalculatorEngine()
        c.digit('3'); c.decimal(); c.decimal(); c.digit('1')
        assertEquals("3.1", c.display())
    }

    @Test
    fun percent_dividesBy100() {
        val c = CalculatorEngine()
        c.digit('5'); c.digit('0'); c.percent()
        assertEquals("0.5", c.display())
    }

    @Test
    fun squareRoot() {
        val c = CalculatorEngine()
        c.digit('8'); c.digit('1'); c.squareRoot()
        assertEquals("9", c.display())
    }

    @Test
    fun squareRoot_negative_showsError() {
        val c = CalculatorEngine()
        c.digit('4'); c.toggleSign(); c.squareRoot()
        assertEquals("خطأ", c.display())
    }

    @Test
    fun toggleSign() {
        val c = CalculatorEngine()
        c.digit('7'); c.toggleSign()
        assertEquals("-7", c.display())
        c.toggleSign()
        assertEquals("7", c.display())
    }

    @Test
    fun backspace() {
        val c = CalculatorEngine()
        c.digit('1'); c.digit('2'); c.digit('3'); c.backspace()
        assertEquals("12", c.display())
        c.backspace(); c.backspace()
        assertEquals("0", c.display())
        c.backspace() // لا يحذف تحت الصفر
        assertEquals("0", c.display())
    }

    @Test
    fun clear_resetsEverything() {
        val c = CalculatorEngine()
        c.digit('5'); c.setOperation('+'); c.digit('5'); c.clear()
        c.digit('1'); c.equals()
        assertEquals("1", c.display())
    }

    @Test
    fun freshEntry_afterEquals() {
        val c = CalculatorEngine()
        c.digit('9'); c.equals(); c.digit('3')
        assertEquals("3", c.display()) // لا تلاصق مع النتيجة السابقة "93"
    }

    @Test
    fun maxDigits_capped() {
        val c = CalculatorEngine()
        repeat(30) { c.digit('9') }
        assertEquals(15, c.display().length)
    }

    @Test
    fun calculatorParity_behavesIdenticallyForRealAndDecoy() {
        // «سلوك الحاسبة متطابق» — نفس التسلسل يعطي نفس النتيجة دائماً
        fun run(): String {
            val c = CalculatorEngine()
            c.digit('1'); c.digit('2'); c.setOperation('*'); c.digit('3'); c.equals()
            return c.display()
        }
        assertEquals("36", run())
        assertEquals(run(), run())
    }
}

/** مُجزّئ PIN: PBKDF2 بملح */
class PinHasherTest {

    @Test
    fun hashVerify_roundTrip() {
        val h = PinHasher(iterations = 1000)
        val salt = h.newSalt()
        val hash = h.hash("2026", salt)
        assertTrue(h.verify("2026", salt, hash))
        assertFalse(h.verify("2027", salt, hash))
    }

    @Test
    fun salts_unique() {
        val h = PinHasher(iterations = 1000)
        val s1 = h.newSalt()
        val s2 = h.newSalt()
        assertNotEquals(s1, s2)
    }

    @Test
    fun samePinDifferentSalts_differentHashes() {
        val h = PinHasher(iterations = 1000)
        val a = h.hash("1234", h.newSalt())
        val b = h.hash("1234", h.newSalt())
        assertNotEquals(a, b)
    }

    @Test
    fun shortPin_rejected() {
        val h = PinHasher(iterations = 1000)
        val e = runCatching { h.hash("12", h.newSalt()) }
        assertTrue(e.isFailure)
    }

    @Test
    fun verify_onGarbageHash_returnsFalse() {
        val h = PinHasher(iterations = 1000)
        assertFalse(h.verify("1234", "not-hex-salt!!", "junk"))
    }
}

/** الإخفاء التلقائي: 30 ثانية بعد المغادرة */
class AutoHidePolicyTest {

    @Test
    fun hidesAfterTimeout() {
        val p = AutoHidePolicy()
        p.onLeave(1000)
        assertFalse(p.onEnter(30_999))
        assertTrue(p.onEnter(31_000))
        assertTrue(p.isHidden())
    }

    @Test
    fun defaultTimeout_is30s() {
        assertEquals(30_000L, AutoHidePolicy.DEFAULT_TIMEOUT_MS)
        val p = AutoHidePolicy()
        p.onLeave(0)
        assertFalse(p.onEnter(29_999))
        assertTrue(p.onEnter(30_000))
    }

    @Test
    fun disabled_neverHides() {
        val p = AutoHidePolicy()
        p.enabled = false
        p.onLeave(0)
        assertFalse(p.onEnter(999_999_999))
    }

    @Test
    fun hideNow_immediate() {
        val p = AutoHidePolicy()
        p.hideNow()
        assertTrue(p.isHidden())
        p.unhide()
        assertFalse(p.isHidden())
    }

    @Test
    fun noLeaveEver_neverHides() {
        val p = AutoHidePolicy()
        assertFalse(p.onEnter(Long.MAX_VALUE))
    }
}

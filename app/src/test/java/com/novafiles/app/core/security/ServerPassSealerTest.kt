package com.novafiles.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.41.1 — اختبارات ختم كلمات مرور الخوادم (P1 من التدقيق):
 * النص الصريح على القرص يُستبدل بختم NFCV1 بمفتاح Keystore، مع ترحيل
 * قراءة قديمة→جديدة يدمر النص الصريح. كل القرارات هنا نقية JVM.
 */
class ServerPassSealerTest {

    private val key = VaultKeys.jvmTestKey()
    private val otherKey = VaultKeys.jvmTestKey(ByteArray(32) { 7 })

    // ---------- الختم والفتح ----------

    @Test
    fun sealUnsealRoundtripArabicAndSymbols() {
        for (secret in listOf("nova", "كلمة-سر-عربية 123!@#", "p@ss w0rd/ذات\\شرطات")) {
            val sealed = ServerPassSealer.seal(key, secret)
            assertTrue("يجب أن تبدو القيمة مختومة", ServerPassSealer.isSealed(sealed))
            assertEquals(secret, ServerPassSealer.unseal(key, sealed))
        }
    }

    @Test
    fun sealedFormatMatchesVaultMagic() {
        // نفس الصيغة الموحدة NFCV1:iv:data — تدقيق مفتوح لا صيغة خاصة جديدة
        val sealed = ServerPassSealer.seal(key, "x")
        assertEquals("NFCV1", sealed.substringBefore(':'))
        assertEquals(3, sealed.split(':').size)
    }

    @Test
    fun everySealUsesFreshIv() {
        val a = ServerPassSealer.seal(key, "same-secret")
        val b = ServerPassSealer.seal(key, "same-secret")
        assertNotEquals("IV عشوائي لكل عملية — لا شفرتان متطابقتان", a, b)
        assertEquals("same-secret", ServerPassSealer.unseal(key, a))
        assertEquals("same-secret", ServerPassSealer.unseal(key, b))
    }

    @Test
    fun tamperedSealedYieldsNull() {
        val sealed = ServerPassSealer.seal(key, "secret")
        val parts = sealed.split(':')
        val data = java.util.Base64.getDecoder().decode(parts[2])
        data[0] = (data[0].toInt() xor 0x01).toByte()
        val tampered = "${parts[0]}:${parts[1]}:${java.util.Base64.getEncoder().encodeToString(data)}"
        assertNull(ServerPassSealer.unseal(key, tampered))
    }

    @Test
    fun wrongKeyYieldsNullNotGarbage() {
        val sealed = ServerPassSealer.seal(key, "top-secret")
        assertNull(ServerPassSealer.unseal(otherKey, sealed))
    }

    @Test
    fun isSealedRejectsNullAndPlaintext() {
        assertFalse(ServerPassSealer.isSealed(null))
        assertFalse(ServerPassSealer.isSealed("plain-password"))
        assertFalse(ServerPassSealer.isSealed("NFCV1-no-colons"))
        assertTrue(ServerPassSealer.isSealed("NFCV1:aaa:bbb"))
    }

    @Test
    fun formatCompatibleWithCredentialVault() {
        // قيمة مختومة عبر خزنة بيانات الاعتماد تُفتح بالختم الجديد والعكس — صيغة واحدة
        val store = HashMap<String, String>()
        val vault = AesGcmVault(key, store)
        vault.save("conn-9-password", "shared-format")
        assertTrue(ServerPassSealer.isSealed(store["conn-9-password"]))
        assertEquals("shared-format", ServerPassSealer.unseal(key, store["conn-9-password"]!!))
        val sealed = ServerPassSealer.seal(key, "reverse")
        store["x"] = sealed
        assertEquals("reverse", vault.load("x"))
    }

    // ---------- القراءة الموحدة ----------

    @Test
    fun decodePrefersSealedOverLegacy() {
        val sealed = ServerPassSealer.seal(key, "from-sealed")
        assertEquals("from-sealed", ServerPassSealer.decode(key, sealed, "from-legacy", "nova"))
    }

    @Test
    fun decodeBrokenSealedFallsBackToDefaultNotLegacy() {
        // ختم فاسد = لا سر — الافتراضي آمن، لا نُرجع نصاً صريحاً قديماً لا نثق بجدةره
        assertEquals("nova", ServerPassSealer.decode(key, "NFCV1:bad:bad", "from-legacy", "nova"))
    }

    @Test
    fun decodeSealedWithoutKeyFailsClosedToDefault() {
        val sealed = ServerPassSealer.seal(key, "hidden")
        assertEquals("nova", ServerPassSealer.decode(null, sealed, "from-legacy", "nova"))
    }

    @Test
    fun decodeLegacyOnlyReturnsLegacyUntilMigration() {
        assertEquals("from-legacy", ServerPassSealer.decode(key, null, "from-legacy", "nova"))
    }

    @Test
    fun decodeEmptyStoreReturnsDefault() {
        assertEquals("nova", ServerPassSealer.decode(key, null, null, "nova"))
    }

    // ---------- خطة الترحيل ----------

    @Test
    fun planSealsLegacyAndDropsIt() {
        val plan = planServerPassMigration(key, "legacy-plain", null)
        assertTrue(plan!!.dropLegacy)
        assertEquals("legacy-plain", ServerPassSealer.unseal(key, plan.sealValue!!))
        assertNotEquals("legacy-plain", plan.sealValue) // لم تعد نصاً صريحاً
    }

    @Test
    fun planWithoutLegacyIsNoop() {
        assertNull(planServerPassMigration(key, null, null))
        assertNull(planServerPassMigration(key, null, ServerPassSealer.seal(key, "already")))
    }

    @Test
    fun planWithExistingSealOnlyDropsLegacy() {
        val existing = ServerPassSealer.seal(key, "sealed-wins")
        val plan = planServerPassMigration(key, "stale-plain", existing)
        assertTrue(plan!!.dropLegacy)
        assertNull("لا يُطغى النص القديم على ختم قائم", plan.sealValue)
    }

    @Test
    fun planBlankLegacyJustDrops() {
        val plan = planServerPassMigration(key, "", null)
        assertTrue(plan!!.dropLegacy)
        assertNull(plan.sealValue)
    }

    @Test
    fun planIsIdempotent() {
        // تشغيل الخطة ثم إعادة الحساب من الحالة الجديدة → لا عمل ثانٍ
        val first = planServerPassMigration(key, "legacy-plain", null)!!
        val after = ServerPassSealer.unseal(key, first.sealValue!!)
        val second = planServerPassMigration(key, null, ServerPassSealer.seal(key, after!!))
        assertNull(second)
    }
}

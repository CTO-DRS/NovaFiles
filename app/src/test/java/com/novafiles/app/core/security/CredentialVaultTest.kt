package com.novafiles.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات خزنة بيانات الاعتماد AES/GCM */
class CredentialVaultTest {

    @Test
    fun saveLoadRoundtripArabicAndEmoji() {
        val store = HashMap<String, String>()
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), store)
        vault.save("conn-1-password", "كلمة السر السرية 🔐123")
        assertEquals("كلمة السر السرية 🔐123", vault.load("conn-1-password"))
    }

    @Test
    fun emptySaveDeletesEntry() {
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), HashMap())
        vault.save("a", "secret")
        vault.save("a", "")
        assertNull(vault.load("a"))
        assertTrue("a" !in vault.ids())
    }

    @Test
    fun loadMissingReturnsNull() {
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), HashMap())
        assertNull(vault.load("nope"))
    }

    @Test
    fun deleteRemovesSecret() {
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), HashMap())
        vault.save("conn-2", "p@ss")
        vault.delete("conn-2")
        assertNull(vault.load("conn-2"))
        assertTrue(vault.ids().isEmpty())
    }

    @Test
    fun wrongKeyYieldsNullNotGarbage() {
        val store = HashMap<String, String>()
        val vaultA = AesGcmVault(VaultKeys.jvmTestKey(ByteArray(32) { 1 }), store)
        vaultA.save("k", "top-secret")
        // مفتاح مختلف على نفس المخزن — فشل GCM يعيد null لا نصاً فاسداً
        val vaultB = AesGcmVault(VaultKeys.jvmTestKey(ByteArray(32) { 2 }), store)
        assertNull(vaultB.load("k"))
    }

    @Test
    fun tamperedCiphertextYieldsNull() {
        val store = HashMap<String, String>()
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), store)
        vault.save("k", "secret")
        val raw = store["k"]!!
        val parts = raw.split(':')
        // اقلب بايتاً في النص المشفر
        val data = java.util.Base64.getDecoder().decode(parts[2])
        data[0] = (data[0].toInt() xor 0x01).toByte()
        store["k"] = "${parts[0]}:${parts[1]}:${java.util.Base64.getEncoder().encodeToString(data)}"
        assertNull(vault.load("k"))
    }

    @Test
    fun everySaveUsesFreshIv() {
        val store = HashMap<String, String>()
        val vault = AesGcmVault(VaultKeys.jvmTestKey(), store)
        vault.save("k1", "same-secret")
        val first = store["k1"]!!
        vault.save("k2", "same-secret")
        val second = store["k2"]!!
        assertNotEquals(first, second) // IV عشوائي — الشفرتان مختلفتان دائماً
        assertEquals("same-secret", vault.load("k1"))
        assertEquals("same-secret", vault.load("k2"))
    }

    @Test
    fun inMemoryVaultMirrorsInterface() {
        val vault = InMemoryVault()
        vault.save("x", "v")
        assertEquals("v", vault.load("x"))
        assertEquals(setOf("x"), vault.ids())
        vault.delete("x")
        assertTrue(vault.ids().isEmpty())
    }
}

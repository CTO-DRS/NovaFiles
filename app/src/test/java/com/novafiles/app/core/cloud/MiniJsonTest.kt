package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `parses nested object with all value kinds`() {
        val json = """{"name":"متصفح","size":12345,"dir":true,"parent":null,"tags":["a","ب"],"ratio":0.75}"""
        val o = MiniJson.parseObject(json)
        assertEquals("متصفح", MiniJson.asString(o["name"]))
        assertEquals(12345L, MiniJson.asLong(o["size"]))
        assertEquals(true, MiniJson.asBool(o["dir"]))
        assertNull(MiniJson.asString(o["parent"]))
        assertEquals(listOf("a", "ب"), MiniJson.asArray(o["tags"]).map { MiniJson.asString(it) })
        assertEquals(0.75, MiniJson.asDouble(o["ratio"])!!, 1e-9)
    }

    @Test
    fun `parses array root with escaped strings`() {
        val v = MiniJson.parse("""[{"path":"/a\"quote\nline"}, "x\\y"]""")
        val arr = MiniJson.asArray(v)
        assertEquals(2, arr.size)
        assertEquals("""/a"quote${'\n'}line""", MiniJson.asString(MiniJson.asObject(arr[0])["path"]))
        assertEquals("""x\y""", MiniJson.asString(arr[1]))
    }

    @Test
    fun `unicode escapes and whitespace tolerance`() {
        val v = MiniJson.parse("  { \"k\" : \"\\u0645\\u0631\" ,\n\t \"n\": -12.5e2 }  ")
        val o = MiniJson.asObject(v)
        assertEquals("مر", MiniJson.asString(o["k"]))
        assertEquals(-1250.0, MiniJson.asDouble(o["n"])!!, 1e-9)
    }

    @Test
    fun `numbers as long and scientific`() {
        assertEquals(1234567890123L, MiniJson.asLong(MiniJson.parse("1234567890123")))
        assertEquals(1500L, MiniJson.asLong(MiniJson.parse("1.5e3")))
        assertEquals(42L, MiniJson.asLong(MiniJson.parse("\"42\"")))
    }

    @Test
    fun `rejects malformed inputs with position`() {
        for (bad in listOf("{", "[1,", "{\"k\":}", "nul", "\"unterminated", "{} trailing", "{\"a\":1,}")) {
            try {
                MiniJson.parse(bad)
                fail("should reject: $bad")
            } catch (e: MiniJson.ParseException) {
                assertTrue(e.position >= 0)
            }
        }
    }

    @Test
    fun `raw control char in string rejected`() {
        try {
            MiniJson.parse("{\"k\":\"a\u0001b\"}")
            fail("control char must be rejected")
        } catch (e: MiniJson.ParseException) {
            // متوقع
        }
    }

    @Test
    fun `duplicate keys last wins`() {
        val o = MiniJson.parseObject("""{"a":1,"a":2}""")
        assertEquals(2L, MiniJson.asLong(o["a"]))
    }

    @Test
    fun `helpers tolerate wrong kinds`() {
        assertNull(MiniJson.asString(MiniJson.parse("42")))
        assertNull(MiniJson.asLong(MiniJson.parse("\"abc\"")))
        assertEquals(emptyList<Any>(), MiniJson.asArray(MiniJson.parse("{}")))
        assertEquals(emptyMap<String, MiniJson.Value>(), MiniJson.asObject(MiniJson.parse("[]")))
    }
}

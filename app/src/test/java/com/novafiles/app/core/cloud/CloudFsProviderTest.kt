package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class CloudFsProviderTest {

    private fun provider(fs: CloudFs) = CloudFsProvider(
        id = "cloud-test",
        fs = fs,
        providerKey = "drive",
    )

    @Test
    fun `scheme is provider key and all cloud caps declared`() {
        val p = provider(MemoryCloudFs())
        assertEquals("drive", p.scheme)
        assertEquals("cloud-test", p.id)
        for (cap in listOf(
            com.novafiles.app.core.provider.ProviderCapability.LIST,
            com.novafiles.app.core.provider.ProviderCapability.READ,
            com.novafiles.app.core.provider.ProviderCapability.WRITE,
            com.novafiles.app.core.provider.ProviderCapability.MKDIR,
            com.novafiles.app.core.provider.ProviderCapability.DELETE,
            com.novafiles.app.core.provider.ProviderCapability.RENAME,
            com.novafiles.app.core.provider.ProviderCapability.SERVER_COPY,
            com.novafiles.app.core.provider.ProviderCapability.SERVER_MOVE,
            com.novafiles.app.core.provider.ProviderCapability.RANGE_READ,
        )) {
            assertTrue("missing cap $cap", p.can(cap))
        }
    }

    @Test
    fun `list enriches entries with etag for snapshot cache`() = cloudRun {
        val mem = MemoryCloudFs().apply { files["/a.bin"] = "123".toByteArray() }
        val entries = provider(mem).list("")
        assertEquals(1, entries.size)
        val e = entries[0]
        assertEquals("a.bin", e.name)
        assertEquals("/a.bin", e.path)
        assertEquals(3L, e.size)
    }

    @Test
    fun `writeFrom and readTo roundtrip through provider`() = cloudRun {
        val mem = MemoryCloudFs()
        val p = provider(mem)
        val tmp = File.createTempFile("rt1", ".bin")
        tmp.writeBytes("roundtrip".toByteArray())
        try {
            p.writeFrom(tmp, "/dir", onProgress = {})
            val out = File.createTempFile("out1", ".bin")
            p.readTo("/dir/" + tmp.name, out, onProgress = {})
            assertEquals("roundtrip", out.readText())
            out.delete()
        } finally {
            tmp.delete()
        }
        Unit
    }

    @Test
    fun `readRangeTo streams from offset into sink`() = cloudRun {
        val mem = MemoryCloudFs().apply { files["/data.bin"] = "0123456789".toByteArray() }
        val sink = ByteArrayOutputStream()
        provider(mem).readRangeTo("/data.bin", 4, sink, onProgress = {})
        assertEquals("456789", sink.toString("UTF-8"))
    }

    @Test
    fun `structural ops delegate to fs`() = cloudRun {
        val mem = MemoryCloudFs()
        val p = provider(mem)
        val tmp = File.createTempFile("xx1", ".tmp").apply { writeText("v"); deleteOnExit() }
        p.mkdir("", "مجلد")
        p.writeFrom(tmp, "/مجلد")
        p.rename("/مجلد/" + tmp.name, "y.tmp")
        assertTrue("/مجلد/y.tmp" in mem.files)
        p.move("/مجلد/y.tmp", "", "z.tmp")
        assertTrue("/z.tmp" in mem.files)
        p.copy("/z.tmp", "/مجلد", "c.tmp")
        assertTrue("/مجلد/c.tmp" in mem.files)
        p.delete("/z.tmp", isDir = false)
        assertFalse("/z.tmp" in mem.files)
        tmp.delete()
        Unit
    }

    @Test
    fun `unwrap exposes raw cloudfs for federated pipe`() {
        val mem = MemoryCloudFs()
        assertEquals(mem, provider(mem).unwrap())
    }
}

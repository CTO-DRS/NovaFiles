package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** نظام ملفات في الذاكرة لاختبارات المستودع — نفس عقد [LocalFileFs] */
class InMemoryFs : VaultFs {
    val files = ConcurrentHashMap<String, ByteArray>()

    init {
        files[""] = ByteArray(0)
    }

    private fun normalize(path: String) = path.trimStart('/')

    override fun exists(path: String): Boolean = files.containsKey(normalize(path))

    override fun size(path: String): Long = files[normalize(path)]?.size?.toLong() ?: 0L

    override fun list(path: String): List<String> {
        val prefix = normalize(path)
        val withSlash = if (prefix.isEmpty()) "" else "$prefix/"
        return files.keys
            .filter { it.startsWith(withSlash) && it.removePrefix(withSlash).isNotEmpty() && it != prefix }
            .map { it.removePrefix(withSlash).substringBefore('/') }
            .distinct()
            .sorted()
    }

    override fun openRead(path: String): InputStream =
        ByteArrayInputStream(files[normalize(path)] ?: throw IllegalStateException("not found: $path"))

    override fun openWrite(path: String): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                files[normalize(path)] = toByteArray()
            }
        }

    override fun delete(path: String): Boolean = files.remove(normalize(path)) != null

    override fun rename(from: String, to: String): Boolean {
        val data = files.remove(normalize(from)) ?: return false
        files[normalize(to)] = data
        return true
    }

    override fun close() { /* لا موارد */ }
}

package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom

/**
 * v0.15 — فهرس الخزنة: شجرة المجلدات والملفات المخزنة (بنية الشجرة محفوظة).
 *
 * كل مدخل له parentId — الجذر "" — فيمكن تمثيل مجلدات داخل مجلدات بلا حدود.
 * الفهرس نفسه يُشفَّر ككتلة واحدة (NVF2 بمفتاح مشتق HKDF) داخل تخزين الخزنة.
 *
 * التسلسل ثنائي مضغوط (DataOutput) — بلا اعتماديات JSON، ويُختبر بالتدوير.
 */
data class VaultEntry(
    val id: String,
    val parentId: String, // "" للجذر
    val name: String,
    val isFolder: Boolean,
    val size: Long = 0L,          // الحجم الأصلي للملفات (0 للمجلدات)
    val createdAt: Long = 0L,     // epoch ms
    val keySalt: ByteArray = ByteArray(16), // ملح اشتقاق مفتاح الملف (عشوائي عند الاستيراد)
) {
    override fun equals(other: Any?): Boolean = other is VaultEntry && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

data class VaultIndex(
    val version: Int = INDEX_VERSION,
    val rootSalt: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
    val entries: List<VaultEntry> = emptyList(),
) {
    fun children(parentId: String): List<VaultEntry> =
        entries.filter { it.parentId == parentId }.sortedWith(
            compareByDescending<VaultEntry> { it.isFolder }.thenBy { it.name.lowercase() },
        )

    fun find(id: String): VaultEntry? = entries.firstOrNull { it.id == id }

    /** سلسلة الأجداد من الجذر إلى الأب (لا تشمل المدخل نفسه) */
    fun pathOf(id: String): List<VaultEntry> {
        val chain = mutableListOf<VaultEntry>()
        var cur = find(id) ?: return chain
        while (cur.parentId.isNotEmpty()) {
            cur = find(cur.parentId) ?: break
            chain.add(0, cur)
        }
        return chain
    }

    /** كل أصول المجلد (لمنع نقل مجلد داخل نفسه) */
    fun isDescendantOf(id: String, ancestorId: String): Boolean {
        var cur = find(id) ?: return false
        while (cur.parentId.isNotEmpty()) {
            if (cur.parentId == ancestorId) return true
            cur = find(cur.parentId) ?: return false
        }
        return false
    }

    /** المسار النصي الكامل للعرض: «صور/عطلات/» */
    fun displayPath(id: String): String = pathOf(id).joinToString("/") { it.name }

    fun serialize(): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { out ->
            out.writeInt(version)
            out.writeInt(rootSalt.size)
            out.write(rootSalt)
            out.writeInt(entries.size)
            for (e in entries) {
                out.writeUTF(e.id)
                out.writeUTF(e.parentId)
                out.writeUTF(e.name)
                out.writeBoolean(e.isFolder)
                out.writeLong(e.size)
                out.writeLong(e.createdAt)
                out.writeInt(e.keySalt.size)
                out.write(e.keySalt)
            }
        }
        return bos.toByteArray()
    }

    companion object {
        const val INDEX_VERSION = 1

        fun deserialize(bytes: ByteArray): VaultIndex {
            DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
                val version = inp.readInt()
                require(version == INDEX_VERSION) { "unsupported vault index version $version" }
                val saltLen = inp.readInt()
                require(saltLen in 16..64) { "corrupted root salt length" }
                val salt = ByteArray(saltLen).also { inp.readFully(it) }
                val count = inp.readInt()
                require(count in 0..1_000_000) { "corrupted entry count" }
                val entries = ArrayList<VaultEntry>(count)
                repeat(count) {
                    val id = inp.readUTF()
                    val parent = inp.readUTF()
                    val name = inp.readUTF()
                    val isFolder = inp.readBoolean()
                    val size = inp.readLong()
                    val created = inp.readLong()
                    val ksLen = inp.readInt()
                    require(ksLen in 0..64) { "corrupted key salt" }
                    val keySalt = ByteArray(ksLen).also { inp.readFully(it) }
                    entries.add(VaultEntry(id, parent, name, isFolder, size, created, keySalt))
                }
                return VaultIndex(version, salt, entries)
            }
        }
    }
}

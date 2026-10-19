package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — مستودع الخزنة 2.0: يدير شجرة مجلدات/ملفات مشفرة فوق أي [VaultFs].
 *
 * البنية في التخزين:
 *   index.bin   — الفهرس المشفر (NVF2 بمفتاح مشتق HKDF من الجذر بملح ثابت معروف)
 *   data/<id>.b — محتوى كل ملف مشفراً (NVF2 بمفتاح مشتق من مفتاح مجلده الأب)
 *
 * الاشتقاق (كل شيء تحت المفتاح الجذر من Keystore):
 *   مفتاح الفهرس = HKDF(root, salt="index",  info="novafiles/vault2/index")
 *   مفتاح مجلد  = HKDF(root, salt=folder.keySalt, info="folder/<id>")
 *   مفتاح ملف   = HKDF(folderKey(parent), salt=file.keySalt, info="file/<id>")
 *
 * نقل ملف بين مجلدين = إعادة تشفير تحت مفتاح المجلد الجديد (خصوصية أقوى:
 * لا ملف يظل مشفراً تحت مفتاح مجلد غادر). كل IO متدفق بذاكرة ثابتة.
 */
class Vault2Repository(
    private val fs: VaultFs,
    masterKey: ByteArray,
) : AutoCloseable {

    private val root: ByteArray = masterKey.copyOf()
    private val random = SecureRandom()

    @Volatile
    var index: VaultIndex = loadOrCreateIndex()
        private set

    // ---------- المفاتيح ----------

    private fun indexKey(): SecretKey =
        SecretKeySpec(
            Hkdf.derive(root, INDEX_SALT, "novafiles/vault2/index".toByteArray(Charsets.UTF_8)),
            "AES",
        )

    fun folderKey(folder: VaultEntry): SecretKey = SecretKeySpec(
        Hkdf.derive(root, folder.keySalt, "novafiles/vault2/folder/${folder.id}".toByteArray(Charsets.UTF_8)),
        "AES",
    )

    private fun fileKey(parentFolder: VaultEntry?, entry: VaultEntry): SecretKey {
        val parentRaw = if (parentFolder != null && parentFolder.isFolder) {
            folderKey(parentFolder).encoded
        } else {
            ByteArray(32)
        }
        val raw = Hkdf.derive(
            parentRaw,
            entry.keySalt,
            "novafiles/vault2/file/${entry.id}".toByteArray(Charsets.UTF_8),
        )
        return SecretKeySpec(raw, "AES")
    }

    // ---------- الفهرس ----------

    private fun loadOrCreateIndex(): VaultIndex {
        if (!fs.exists(INDEX_PATH)) return VaultIndex()
        fs.openRead(INDEX_PATH).use { input ->
            val plain = ByteArrayOutputStream()
            try {
                ChunkedCipher.decrypt(input, plain, indexKey())
                return VaultIndex.deserialize(plain.toByteArray())
            } catch (e: Exception) {
                // فهرس موجود لكنه لا يُفك — مفتاح غيّر أو تلف: نرمي بدل الكتابة فوقه صمتاً
                throw ChunkedCipher.CorruptedVaultFile("vault index will not decrypt: ${e.message}")
            }
        }
    }

    private fun saveIndex() {
        fs.openWrite(INDEX_PATH).use { out ->
            ChunkedCipher.encrypt(ByteArrayInputStream(index.serialize()), out, indexKey())
        }
    }

    // ---------- المجلدات ----------

    fun createFolder(parentId: String, name: String): VaultEntry {
        require(name.isNotBlank() && !name.contains('/')) { "invalid folder name" }
        index.find(parentId) ?: if (parentId.isNotEmpty()) error("parent not found") else Unit
        if (index.children(parentId).any { it.name.equals(name, ignoreCase = false) }) {
            error("name exists: $name")
        }
        val entry = VaultEntry(
            id = newId("d"),
            parentId = parentId,
            name = name,
            isFolder = true,
            createdAt = System.currentTimeMillis(),
            keySalt = ByteArray(16).also { random.nextBytes(it) },
        )
        index = index.copy(entries = index.entries + entry)
        saveIndex()
        return entry
    }

    // ---------- الملفات ----------

    /** استيراد محتوى من تدفق — يُشفَّر تحت مفتاح مجلده الهدف. @return المدخل + الحجم الأصلي */
    fun importFile(parentId: String, name: String, content: InputStream): VaultEntry {
        require(name.isNotBlank() && !name.contains('/')) { "invalid file name" }
        require(parentId.isEmpty() || index.find(parentId)?.isFolder == true) { "parent folder not found" }
        val uniqueName = uniqueName(parentId, name)
        val entry = VaultEntry(
            id = newId("f"),
            parentId = parentId,
            name = uniqueName,
            isFolder = false,
            createdAt = System.currentTimeMillis(),
            keySalt = ByteArray(16).also { random.nextBytes(it) },
        )
        val parent = index.find(parentId)
        val key = fileKey(parent, entry)
        val size = fs.openWrite(dataPath(entry.id)).use { out ->
            ChunkedCipher.encrypt(content, out, key)
        }
        index = index.copy(entries = index.entries + entry.copy(size = size))
        saveIndex()
        return index.entries.last { it.id == entry.id }
    }

    /** فك محتوى ملف إلى تدفق هدف */
    fun exportFile(entryId: String, destination: OutputStream): Long {
        val entry = index.find(entryId) ?: error("entry not found: $entryId")
        require(!entry.isFolder) { "cannot export a folder" }
        val parent = index.find(entry.parentId)
        fs.openRead(dataPath(entry.id)).use { input ->
            return ChunkedCipher.decrypt(input, destination, fileKey(parent, entry))
        }
    }

    /** فتح جزء واحد للمعاينة (مثلاً أول 64KiB لصورة مصغرة) */
    fun readChunk(entryId: String, chunkIndex: Long): ByteArray {
        val entry = index.find(entryId) ?: error("entry not found")
        val parent = index.find(entry.parentId)
        fs.openRead(dataPath(entry.id)).use { input ->
            return ChunkedCipher.decryptChunk(input, fileKey(parent, entry), chunkIndex)
        }
    }

    /** نقل مدخل (ملف أو مجلد مع أطفاله) — الملفات العابرة للمجلدات تُعاد تشفيرتها */
    fun move(entryId: String, newParentId: String) {
        val entry = index.find(entryId) ?: error("entry not found")
        require(entryId != newParentId) { "cannot move into itself" }
        if (newParentId.isNotEmpty()) {
            val target = index.find(newParentId) ?: error("target not found")
            require(target.isFolder) { "target is not a folder" }
            require(!index.isDescendantOf(newParentId, entryId)) { "cannot move into own subtree" }
        }
        if (entry.parentId == newParentId) return
        if (entry.isFolder) {
            index = index.copy(entries = index.entries.map { if (it.id == entryId) it.copy(parentId = newParentId) else it })
            saveIndex()
        } else {
            // إعادة تشفير متدفقة تحت مفتاح المجلد الجديد (الملح نفسه يكفي — تغيّر الأب يغيّر المفتاح)
            val oldParent = index.find(entry.parentId)
            val oldKey = fileKey(oldParent, entry)
            val newKey = fileKey(index.find(newParentId), entry)
            val tmpPath = "${dataPath(entry.id)}.mv"
            val size = fs.openWrite(tmpPath).use { out ->
                fs.openRead(dataPath(entry.id)).use { input ->
                    ChunkedCipher.recrypt(input, out, oldKey, newKey)
                }
            }
            fs.delete(dataPath(entry.id))
            fs.rename(tmpPath, dataPath(entry.id))
            index = index.copy(
                entries = index.entries.map { if (it.id == entryId) entry.copy(parentId = newParentId, size = size) else it },
            )
            saveIndex()
        }
    }

    /** إعادة تسمية مدخل (داخل نفس الأب) */
    fun rename(entryId: String, newName: String) {
        require(newName.isNotBlank() && !newName.contains('/')) { "invalid name" }
        val entry = index.find(entryId) ?: error("entry not found")
        if (index.children(entry.parentId).any { it.id != entryId && it.name == newName }) {
            error("name exists: $newName")
        }
        index = index.copy(
            entries = index.entries.map { if (it.id == entryId) it.copy(name = newName) else it },
        )
        saveIndex()
    }

    /** حذف شجري (مجلد مع كل أطفاله) + مسح بيانات الملفات المشفرة */
    fun deleteRecursive(entryId: String): Int {
        val entry = index.find(entryId) ?: error("entry not found")
        val doomed = mutableSetOf(entry.id)
        var grew = true
        while (grew) {
            grew = false
            for (e in index.entries) {
                if (e.parentId in doomed && e.id !in doomed) {
                    doomed.add(e.id)
                    grew = true
                }
            }
        }
        var files = 0
        for (id in doomed) {
            val e = index.find(id) ?: continue
            if (!e.isFolder) {
                files++
                fs.delete(dataPath(e.id))
            }
        }
        index = index.copy(entries = index.entries.filter { it.id !in doomed })
        saveIndex()
        return files
    }

    // ---------- إحصاءات ----------

    fun fileCount(): Int = index.entries.count { !it.isFolder }

    fun totalSize(): Long = index.entries.filter { !it.isFolder }.sumOf { it.size }

    // ---------- الاسترجاع ----------

    /** تصدير المفتاح الجذر لغرض بناء نسخة الاسترجاع (يُغلّف فوراً بمفتاح العبارة) */
    fun exportMasterKey(): ByteArray = root.copyOf()

    /** استبدال الفهرس كاملاً (مسار استيراد النسخة الاحتياطية) وإعادة حفظه مشفراً */
    fun replaceIndex(newIndex: VaultIndex) {
        index = newIndex
        saveIndex()
    }

    // ---------- أدوات ----------

    private fun uniqueName(parentId: String, name: String): String {
        val taken = index.children(parentId).map { it.name }.toHashSet()
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun newId(prefix: String): String {
        val b = ByteArray(9).also { random.nextBytes(it) }
        return prefix + b.joinToString("") { "%02x".format(it) }
    }

    private fun dataPath(id: String) = "data/$id.b"

    override fun close() { /* لا موارد — fs يغلقها صاحبها */ }

    companion object {
        const val INDEX_PATH = "index.bin"
        val INDEX_SALT: ByteArray = "novafiles-vault2-index-salt".toByteArray(Charsets.UTF_8)
    }
}

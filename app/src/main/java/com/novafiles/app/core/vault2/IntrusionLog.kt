package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — سجل التسلل: كل محاولة دخول فاشلة تُقيَّد بختم زمني وربما صورة
 * (ملف مشفر منفصل يُشار إليه بمعرّفه). السجل نفسه مخزّن مشفراً (NVF2).
 *
 * سقف 200 مدخل — الأقدم يُحذف عند التهيئة (منع تضخم بلا حدود).
 */
class IntrusionLog(
    private val fs: VaultFs,
    masterKey: ByteArray,
) : AutoCloseable {

    enum class Kind { WRONG_PIN, WRONG_BIOMETRIC, DECOY_OPENED, LOCK_OVERRIDE }

    data class Entry(
        val timestamp: Long,
        val kind: Kind,
        val detail: String = "",
        val photoRef: String? = null, // معرّف ملف الصورة المشفرة في fs
    )

    private val key = SecretKeySpec(
        Hkdf.derive(masterKey, LOG_SALT, "novafiles/vault2/intrusion-log".toByteArray(Charsets.UTF_8)),
        "AES",
    )
    private val random = SecureRandom()
    private var entries: MutableList<Entry> = load()

    fun all(): List<Entry> = entries.toList()

    fun record(kind: Kind, detail: String = "", photoContent: InputStream? = null): Entry {
        val photoRef = photoContent?.let { storePhoto(it) }
        val entry = Entry(System.currentTimeMillis(), kind, detail, photoRef)
        entries.add(0, entry) // الأحدث أولاً
        if (entries.size > MAX_ENTRIES) entries = entries.subList(0, MAX_ENTRIES).toMutableList()
        persist()
        return entry
    }

    /** فتح صورة مدخل مشفرة — null إن لم توجد */
    fun openPhoto(entry: Entry, destination: OutputStream): Boolean {
        val ref = entry.photoRef ?: return false
        if (!fs.exists(photoPath(ref))) return false
        fs.openRead(photoPath(ref)).use { input ->
            ChunkedCipher.decrypt(input, destination, key)
        }
        return true
    }

    fun clear() {
        entries.forEach { e -> e.photoRef?.let { fs.delete(photoPath(it)) } }
        entries = mutableListOf()
        persist()
    }

    fun size(): Int = entries.size

    // ---------- التخزين ----------

    private fun storePhoto(content: InputStream): String {
        val ref = "p" + ByteArray(9).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        fs.openWrite(photoPath(ref)).use { out ->
            ChunkedCipher.encrypt(content, out, key)
        }
        return ref
    }

    private fun persist() {
        fs.openWrite(LOG_PATH).use { out ->
            val bytes = ByteArrayOutputStream().use { bos ->
                java.io.DataOutputStream(bos).use { out2 ->
                    out2.writeInt(entries.size)
                    entries.forEach { e ->
                        out2.writeLong(e.timestamp)
                        out2.writeUTF(e.kind.name)
                        out2.writeUTF(e.detail)
                        out2.writeBoolean(e.photoRef != null)
                        e.photoRef?.let { out2.writeUTF(it) }
                    }
                }
                bos.toByteArray()
            }
            ChunkedCipher.encrypt(ByteArrayInputStream(bytes), out, key)
        }
    }

    private fun load(): MutableList<Entry> {
        if (!fs.exists(LOG_PATH)) return mutableListOf()
        return try {
            fs.openRead(LOG_PATH).use { input ->
                val plain = ByteArrayOutputStream()
                ChunkedCipher.decrypt(input, plain, key)
                java.io.DataInputStream(ByteArrayInputStream(plain.toByteArray())).use { inp ->
                    val count = inp.readInt()
                    val list = mutableListOf<Entry>()
                    repeat(count) {
                        val ts = inp.readLong()
                        val kind = runCatching { Kind.valueOf(inp.readUTF()) }.getOrDefault(Kind.WRONG_PIN)
                        val detail = inp.readUTF()
                        val hasPhoto = inp.readBoolean()
                        val photo = if (hasPhoto) inp.readUTF() else null
                        list.add(Entry(ts, kind, detail, photo))
                    }
                    list
                }
            }
        } catch (e: Exception) {
            mutableListOf() // سجل تالف → يبدأ فارغاً (لا يمنع فتح الخزنة أبداً)
        }
    }

    private fun photoPath(ref: String) = "photos/$ref.b"

    override fun close() { /* لا موارد */ }

    companion object {
        const val LOG_PATH = "intrusion.log.b"
        const val MAX_ENTRIES = 200
        val LOG_SALT: ByteArray = "novafiles-vault2-log-salt".toByteArray(Charsets.UTF_8)
    }
}

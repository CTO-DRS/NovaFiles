package com.novafiles.app.core.vault2

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.15 — التشفير المتدفق المقسّم (NVF2): بديل v0.3 الذي كان يقرأ الملف كاملاً في الذاكرة.
 *
 * الصيغة:
 *   الترويسة: [ magic "NVF2" 4B ][ version 1B ][ rootIV 12B ][ chunkSize 4B BE ]  (21B)
 *   المتن:    [ جزء 0 = نص مشفر + وسم GCM 16B ][ جزء 1 ]...   (كل جزء ≤ chunkSize أصلياً)
 *   الخاتمة:  [ magic "NVF2T" 5B ][ originalSize 8B BE ]  (13B — مكتوبة آخراً لدعم التدفق)
 *
 *  - nonce الجزء n: آخر 4 بايت من rootIV تُدمج مع العداد n بـ XOR
 *    → 2^32 جزءاً لكل ملف (256 TiB عند 64 KiB) دون تكرار nonce أبداً
 *  - ذاكرة ثابتة: جزء واحد فقط (64 KiB افتراضياً) في الذاكرة لحظياً — ملف 1 GB لا يستهلك أكثر
 *  - علامة مصادقة مستقلة لكل جزء: يُكشف التلف/القص عند حد الجزء بالضبط
 *  - قراءة عشوائية: [decryptChunk] يفك جزءاً واحداً فقط للمعاينة
 */
object ChunkedCipher {

    const val MAGIC = "NVF2"
    const val MAGIC_END = "NVF2T"
    const val VERSION: Byte = 1
    const val TAG_BITS = 128
    const val TAG_BYTES = 16
    const val IV_LEN = 12
    const val DEFAULT_CHUNK = 64 * 1024
    const val HEADER_LEN = 4 + 1 + IV_LEN + 4 // 21
    const val TRAILER_LEN = 5 + 8             // 13

    class CorruptedVaultFile(message: String) : IllegalStateException(message)

    // ---------- تشفير متدفق ----------

    /**
     * يشفّر [input] إلى [output] بصيغة NVF2 — ذاكرة ثابتة، ترويسة وخاتمة صحيحتان.
     * @return الحجم الأصلي (قبل التشفير)
     */
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, chunkSize: Int = DEFAULT_CHUNK): Long {
        require(chunkSize in 1024..4 * 1024 * 1024) { "chunkSize out of range: $chunkSize" }
        val rootIv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION.toInt())
        output.write(rootIv)
        output.writeBE32(chunkSize)
        var original = 0L
        var counter = 0L
        val buf = ByteArray(chunkSize)
        while (true) {
            val read = input.readN(buf)
            if (read <= 0) break
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(rootIv, counter)))
            output.write(cipher.doFinal(buf.copyOf(read)))
            original += read
            counter++
        }
        output.write(MAGIC_END.toByteArray(Charsets.US_ASCII))
        output.writeBE64(original)
        output.flush()
        return original
    }

    // ---------- فك التشفير ----------

    /** فك متدفق كامل — يرمي [CorruptedVaultFile] عند أول جزء فاسد أو قصّ. @return الحجم الأصلي */
    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey): Long {
        val header = readHeader(input)
        val fullLen = header.chunkSize + TAG_BYTES
        var total = 0L
        var counter = 0L
        var trailerSize = -1L
        val buf = ByteArray(fullLen)
        while (true) {
            val read = input.readN(buf)
            if (read == -1) break
            if (read == TRAILER_LEN && isTrailer(buf, read)) {
                // خاتمة وحدها (ملف فارغ أو حد جزء كامل)
                trailerSize = trailerValue(buf, TRAILER_LEN)
                break
            }
            if (read < TRAILER_LEN) throw CorruptedVaultFile("truncated tail (${read}B)")
            var chunkBytes = read
            if (read < fullLen) {
                // القراءة القصيرة الأخيرة تحمل آخر جزء + الخاتمة معاً
                if (!isTrailer(buf, read)) throw CorruptedVaultFile("missing trailer")
                chunkBytes = read - TRAILER_LEN
                trailerSize = trailerValue(buf, read)
            }
            if (chunkBytes < TAG_BYTES) throw CorruptedVaultFile("truncated chunk #$counter")
            val plain = try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(header.rootIv, counter)))
                cipher.doFinal(buf.copyOf(chunkBytes))
            } catch (e: Exception) {
                throw CorruptedVaultFile("chunk #$counter failed authentication")
            }
            output.write(plain)
            total += plain.size
            counter++
        }
        if (trailerSize == -1L) throw CorruptedVaultFile("trailer missing")
        if (total != trailerSize) {
            throw CorruptedVaultFile("size mismatch: trailer=$trailerSize actual=$total")
        }
        return total
    }

    /**
     * إعادة تشفير متدفق (نقل بين المجلدات): يفك أجزاء NVF2 بمفتاح قديم ويعيد
     * شفروها فوراً بمفتاح جديد — نفس حجم الجزء للمصدر والمقصود، ذاكرة ثابتة.
     * @return الحجم الأصلي
     */
    fun recrypt(input: InputStream, output: OutputStream, oldKey: SecretKey, newKey: SecretKey): Long {
        val header = readHeader(input)
        val fullLen = header.chunkSize + TAG_BYTES
        val rootIv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        output.write(MAGIC.toByteArray(Charsets.US_ASCII))
        output.write(VERSION.toInt())
        output.write(rootIv)
        output.writeBE32(header.chunkSize)
        var total = 0L
        var counter = 0L
        var trailerSize = -1L
        val buf = ByteArray(fullLen)
        while (true) {
            val read = input.readN(buf)
            if (read == -1) break
            if (read == TRAILER_LEN && isTrailer(buf, read)) {
                trailerSize = trailerValue(buf, TRAILER_LEN)
                break
            }
            if (read < TRAILER_LEN) throw CorruptedVaultFile("truncated tail (${read}B)")
            var chunkBytes = read
            if (read < fullLen) {
                if (!isTrailer(buf, read)) throw CorruptedVaultFile("missing trailer")
                chunkBytes = read - TRAILER_LEN
                trailerSize = trailerValue(buf, read)
            }
            if (chunkBytes < TAG_BYTES) throw CorruptedVaultFile("truncated chunk #$counter")
            val plain = try {
                val dec = Cipher.getInstance("AES/GCM/NoPadding")
                dec.init(Cipher.DECRYPT_MODE, oldKey, GCMParameterSpec(TAG_BITS, nonceFor(header.rootIv, counter)))
                dec.doFinal(buf.copyOf(chunkBytes))
            } catch (e: Exception) {
                throw CorruptedVaultFile("chunk #$counter failed authentication")
            }
            val enc = Cipher.getInstance("AES/GCM/NoPadding")
            enc.init(Cipher.ENCRYPT_MODE, newKey, GCMParameterSpec(TAG_BITS, nonceFor(rootIv, counter)))
            output.write(enc.doFinal(plain))
            total += plain.size
            counter++
        }
        if (trailerSize == -1L) throw CorruptedVaultFile("trailer missing")
        if (total != trailerSize) throw CorruptedVaultFile("recrypt size mismatch")
        output.write(MAGIC_END.toByteArray(Charsets.US_ASCII))
        output.writeBE64(total)
        output.flush()
        return total
    }

    /** فك جزء واحد بالرقم [index] — معاينة عشوائية دون فك الملف كله */
    fun decryptChunk(input: InputStream, key: SecretKey, index: Long): ByteArray {
        val header = readHeader(input)
        require(index >= 0) { "negative chunk index" }
        val fullLen = header.chunkSize + TAG_BYTES
        // readHeader استهلك الترويسة بالفعل — نقفز الأجزاء السابقة فقط
        input.skipFully(index * fullLen)
        val buf = ByteArray(fullLen)
        val read = input.readN(buf)
        if (read == -1) throw CorruptedVaultFile("chunk #$index out of range")
        var chunkBytes = read
        if (read < fullLen) {
            if (!isTrailer(buf, read)) throw CorruptedVaultFile("missing trailer at chunk #$index")
            chunkBytes = read - TRAILER_LEN
        }
        if (chunkBytes < TAG_BYTES) throw CorruptedVaultFile("truncated chunk #$index")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(header.rootIv, index)))
            cipher.doFinal(buf.copyOf(chunkBytes))
        } catch (e: Exception) {
            throw CorruptedVaultFile("chunk #$index failed authentication")
        }
    }

    // ---------- الترويسة والخاتمة ----------

    data class ChunkHeader(val rootIv: ByteArray, val chunkSize: Int)

    fun readHeader(input: InputStream): ChunkHeader {
        val head = ByteArray(HEADER_LEN)
        if (input.readN(head) != HEADER_LEN) throw CorruptedVaultFile("header truncated")
        if (String(head, 0, 4, Charsets.US_ASCII) != MAGIC) throw CorruptedVaultFile("not an NVF2 file")
        if (head[4] != VERSION) throw CorruptedVaultFile("unsupported version ${head[4]}")
        val iv = head.copyOfRange(5, 5 + IV_LEN)
        val chunkSize = ((head[17].toInt() and 0xFF) shl 24) or ((head[18].toInt() and 0xFF) shl 16) or
            ((head[19].toInt() and 0xFF) shl 8) or (head[20].toInt() and 0xFF)
        require(chunkSize in 1024..4 * 1024 * 1024) { "corrupted chunkSize: $chunkSize" }
        return ChunkHeader(iv, chunkSize)
    }

    /** يقرأ الخاتمة الأخيرة بعد انتهاء الأجزاء — يتحقق من الحجم الأصلي */
    fun readTrailer(input: InputStream): Long {
        val tail = ByteArray(TRAILER_LEN)
        if (input.readN(tail) != TRAILER_LEN || !isTrailer(tail, TRAILER_LEN)) {
            throw CorruptedVaultFile("trailer missing")
        }
        var size = 0L
        for (i in 5 until TRAILER_LEN) size = (size shl 8) or (tail[i].toLong() and 0xFF)
        return size
    }

    private fun isTrailer(buf: ByteArray, upTo: Int): Boolean {
        if (upTo < TRAILER_LEN) return false
        val m = MAGIC_END.toByteArray(Charsets.US_ASCII)
        for (i in m.indices) if (buf[upTo - TRAILER_LEN + i] != m[i]) return false
        return true
    }

    private fun trailerValue(buf: ByteArray, upTo: Int): Long {
        var size = 0L
        for (i in (upTo - 8) until upTo) size = (size shl 8) or (buf[i].toLong() and 0xFF)
        return size
    }

    fun nonceFor(rootIv: ByteArray, counter: Long): ByteArray {
        val nonce = rootIv.copyOf()
        for (i in 0 until 4) {
            nonce[IV_LEN - 1 - i] =
                (nonce[IV_LEN - 1 - i].toInt() xor (((counter ushr (8 * i)).toInt()) and 0xFF)).toByte()
        }
        return nonce
    }

    /** مفتاح ملف: HKDF تحت المفتاح الجذر بملح خاص بالملف ومعرّفه */
    fun keyFor(master: ByteArray, salt: ByteArray, fileId: String): SecretKey {
        val raw = Hkdf.derive(master, salt, "novafiles/vault2/file/$fileId".toByteArray(Charsets.UTF_8))
        return SecretKeySpec(raw, "AES")
    }

    // ---------- أدوات تدفق ----------

    internal fun InputStream.readN(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = read(buf, off, buf.size - off)
            if (r == -1) break
            off += r
        }
        return if (off == 0) -1 else off
    }

    private fun OutputStream.writeBE32(v: Int) {
        write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
    }

    private fun OutputStream.writeBE64(v: Long) {
        write(ByteArray(8) { i -> (v ushr (8 * (7 - i))).toByte() })
    }

    private fun InputStream.skipFully(n: Long) {
        var remaining = n
        while (remaining > 0) {
            val s = skip(remaining)
            if (s <= 0) throw EOFException("skip failed, $remaining bytes remaining")
            remaining -= s
        }
    }
}

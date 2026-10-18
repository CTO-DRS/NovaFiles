package com.novafiles.app.core.fs

import com.novafiles.app.data.db.FileIndexEntity
import com.novafiles.app.data.db.NovaDb
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * مفهرس التخزين الخلفي للبحث الفوري:
 * - يمشي شجرة /storage/emulated/0 على دفعات ويدخلها في جدول file_index (Room-FTS4)
 * - البحث الفوري يقرأ من الفهرس (بادئة FTS ثم LIKE لأي جزء من الاسم)
 * - الفحص الحيّ في SearchEngine يبقى احتياطاً قبل جاهزية الفهرس
 */
object FileIndexer {

    const val ROOT = "/storage/emulated/0"
    private const val BATCH = 500
    private const val MAX_SCAN_FILES = 200_000

    private val mutex = Mutex()

    private val _indexing = MutableStateFlow(false)
    val indexing: StateFlow<Boolean> = _indexing

    private val _progress = MutableStateFlow(0L)
    val progress: StateFlow<Long> = _progress

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    /** يفهرس مرة واحدة إن كان الفهرس فارغاً (آمن للاستدعاء المتكرر) */
    suspend fun ensureIndexed(db: NovaDb) {
        if (_ready.value || _indexing.value) return
        mutex.withLock {
            if (_ready.value || _indexing.value) return
            if (db.fileIndexDao().count() > 0) {
                _ready.value = true
                return
            }
            index(db)
        }
    }

    /** إعادة بناء كامل للفهرس (يدوياً من ورقة إجراءات البحث) */
    suspend fun rebuild(db: NovaDb) {
        mutex.withLock { index(db) }
    }

    private suspend fun index(db: NovaDb) = withContext(Dispatchers.IO) {
        _indexing.value = true
        _progress.value = 0
        try {
            val dao = db.fileIndexDao()
            dao.clearAll()
            var total = 0L
            var scanned = 0
            var batch = ArrayList<FileIndexEntity>(BATCH)
            val stack = ArrayDeque<File>()
            stack.add(File(ROOT))
            while (stack.isNotEmpty() && scanned < MAX_SCAN_FILES) {
                val dir = stack.removeFirstOrNull() ?: continue
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    scanned++
                    val hidden = f.name.startsWith(".")
                    if (hidden) continue
                    if (f.isDirectory) {
                        stack.add(f)
                    }
                    batch.add(
                        FileIndexEntity(
                            path = f.absolutePath,
                            name = f.name,
                            parent = f.parent ?: "",
                            size = if (f.isDirectory) 0L else f.length(),
                            modified = f.lastModified(),
                            isDirectory = f.isDirectory,
                        ),
                    )
                    total++
                    if (batch.size >= BATCH) {
                        dao.upsertAll(batch)
                        _progress.value = total
                        batch = ArrayList(BATCH)
                    }
                }
            }
            if (batch.isNotEmpty()) dao.upsertAll(batch)
            _progress.value = total
            _ready.value = total > 0
        } finally {
            _indexing.value = false
        }
    }

    /**
     * بحث فوري من الفهرس: بادئة FTS ثم LIKE احتياطياً، ثم ترشيح النوع/الحجم/العمر.
     * يعيد قائمة ملفات حقيقية جاهزة للعرض.
     */
    suspend fun search(db: NovaDb, query: SearchEngine.Query): List<File> = withContext(Dispatchers.IO) {
        val dao = db.fileIndexDao()
        val needle = query.text.trim()
        val rows: List<FileIndexEntity> = if (needle.isEmpty()) {
            dao.recent(maxOf(query.limit, 2000))
        } else {
            val match = ftsQueryString(needle)
            val fts = runCatching { dao.ftsMatch(match, query.limit * 2) }.getOrDefault(emptyList())
            if (fts.isNotEmpty()) fts else dao.likeMatch(needle, query.limit * 2)
        }

        val cutoff = query.maxAgeDays?.let { System.currentTimeMillis() - it * 86_400_000L }
        val out = ArrayList<File>(minOf(rows.size, query.limit))
        for (r in rows) {
            if (!query.showHidden && r.name.startsWith(".")) continue
            val f = File(r.path)
            if (!FileType.filterGroup(
                    query.type,
                    name = r.name,
                    isDirectory = r.isDirectory,
                )
            ) continue
            if (r.size < query.minSizeBytes) continue
            if (cutoff != null && r.modified < cutoff) continue
            out.add(f)
            if (out.size >= query.limit) break
        }
        out
    }

    /** تحويل نص المستخدم إلى استعلام FTS آمن: بادئة على آخر كلمة */
    private fun ftsQueryString(needle: String): String {
        val tokens = needle.split(Regex("\\s+"))
            .map { t -> t.filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "\"\""
        val head = tokens.dropLast(1).joinToString(" ") { "\"$it\"" }
        val last = tokens.last()
        return if (head.isEmpty()) "$last*" else "$head $last*"
    }
}

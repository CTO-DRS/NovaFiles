package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * v0.16 — مزامنة الكاميرا (اتجاه واحد): صور/فيديوهات جديدة ← مجلد سحابي
 * مختار — تماماً كـ Google Photos، لكن على مخزن المستخدم (Drive/Dropbox/OneDrive).
 *
 * القيود الافتراضية (وفق الخارطة): Wi-Fi + شحن — تُقيَّم عبر [SyncEnvironment].
 * التتبع الدقيق (exact-once): دفترة [SyncLedger] مفتاحها هوية المصدر (URI)
 * وقيمتها هاش محتوى SHA-256 + الحجم — الملف يُرفع مرة واحدة بالضبط:
 *  - نفس (hash,size) في الدفترة ← تخطٍّ فوري بلا شبكة
 *  - الرفع ينجح ← سجل بالمسار البعيد وetag
 *  - الرفع يفشل ← بلا سجل — الجولة القادمة تعيد المحاولة (idempotent)
 *  - تغيّر المحتوى (نادر للكاميرا) ← هاش جديد ← يُرفع كنسخة جديدة
 *
 * البنية المسماة: مجلد أوميقي اختياري (2026/05/IMG_1234.jpg) عبر [naming].
 */
object CameraSync {

    /** عنصر وسائط مصدري — قادم من MediaStore في طبقة أندرويد */
    data class MediaItem(
        /** هوية مستقرة: content:// URI أو المسار */
        val key: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val isVideo: Boolean,
        /** يفتح محتوى العنصر للتدفق (الهاش ثم الرفع) */
        val open: () -> InputStream,
        /** v0.32 — المصدر المُصنّف (يحدد المجلد الفرعي عند تعدد المصادر) */
        val source: CameraSource = CameraSource.CAMERA,
    )

    /** سجل دفترة — عنصر مُزامَن سابقاً */
    data class LedgerRecord(
        val key: String,
        val contentHash: String,
        val size: Long,
        val remotePath: String,
        val remoteRef: String?,
        val uploadedAt: Long,
    )

    /** دفترة المزامنة — الإنتاج: Room، الاختبارات: ذاكرة */
    interface SyncLedger {
        suspend fun get(key: String): LedgerRecord?
        suspend fun put(record: LedgerRecord)
        suspend fun all(): List<LedgerRecord>
        suspend fun remove(key: String)
    }

    class InMemoryLedger : SyncLedger {
        val map = HashMap<String, LedgerRecord>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(record: LedgerRecord) { map[record.key] = record }
        override suspend fun all() = map.values.toList()
        override suspend fun remove(key: String) { map.remove(key) }
    }

    /** بيئة الجهاز — تُقيَّم القيود ضدها */
    data class SyncEnvironment(
        val networkMetered: Boolean,   // شبكة مقيسة (بيانات جوال)
        val charging: Boolean,
        val batteryPct: Int,
    )

    /** قيود المزامنة — الافتراضي وفق الخارطة: Wi-Fi + شحن */
    data class SyncConstraints(
        val wifiOnly: Boolean = true,
        val requireCharging: Boolean = true,
        val minBatteryPct: Int = 20,
    ) {
        /** هل البيئة تسمح بالمزامنة الآن؟ */
        fun satisfied(env: SyncEnvironment): Boolean {
            if (wifiOnly && env.networkMetered) return false
            if (requireCharging && !env.charging) return false
            if (env.batteryPct < minBatteryPct) return false
            return true
        }
    }

    /** استراتيجية تسمية الوجهة */
    enum class Naming { FLAT, YEAR_MONTH }

    /**
     * v0.32 — مصادر مزامنة الكاميرا المتعددة: بدل مصدر واحد (الكاميرا)،
     * تُصنّف الوسائط حسب RELATIVE_PATH إلى كاميرا / واتساب / لقطات شاشة،
     * ويُحفظ اسم المصدر كمجلد فرعي تحت الهدف (نفس ضمانة exact-once).
     */
    enum class CameraSource(val id: String) {
        CAMERA("CAMERA"),
        WHATSAPP("WHATSAPP"),
        SCREENSHOTS("SCREENSHOTS"),
        ;

        /** مجلد فرعي ثابت ASCII تحت الهدف (سلامة عبر المزودات) */
        val folderLabel: String
            get() = when (this) {
                CAMERA -> "Camera"
                WHATSAPP -> "WhatsApp"
                SCREENSHOTS -> "Screenshots"
            }

        companion object {
            /** تحليل CSV مخزن — الفارغ/الغريب يعود للكاميرا (توافق v0.16) */
            fun fromCsv(csv: String?): Set<CameraSource> {
                val parsed = csv.orEmpty().split(',')
                    .mapNotNull { s -> entries.firstOrNull { it.id == s.trim() } }
                    .toSet()
                return parsed.ifEmpty { setOf(CAMERA) }
            }

            fun toCsv(sources: Set<CameraSource>): String =
                sources.joinToString(",") { it.id }

            /**
             * تصنيف مسار نسبي من MediaStore — قاعدة حتمية:
             * مسار واتساب ← WHATSAPP، لقطات شاشة/تسجيل ← SCREENSHOTS،
             * وكل ما عدا ذلك (DCIM/Camera أو غير معروف) ← الكاميرا.
             */
            fun classify(relativePath: String): CameraSource {
                val p = relativePath.replace('\\', '/').lowercase()
                return when {
                    p.contains("whatsapp") -> WHATSAPP
                    p.contains("screenshot") || p.contains("screencast") || p.endsWith("/screens") -> SCREENSHOTS
                    else -> CAMERA
                }
            }
        }
    }

    /** سياسة المزامنة كاملة */
    data class SyncPolicy(
        val targetFolder: String,      // المسار السحابي الهدف
        val naming: Naming = Naming.YEAR_MONTH,
        val constraints: SyncConstraints = SyncConstraints(),
        /** v0.32 — مجلد فرعي لكل مصدر (عند تعدد المصادر فقط) */
        val multiSource: Boolean = false,
    )

    /** تقرير جولة مزامنة */
    data class SyncReport(
        val uploaded: List<String>,
        val skipped: List<String>,
        val failed: Map<String, String>,
        val constraintsBlocked: Boolean = false,
    ) {
        companion object {
            val BLOCKED = SyncReport(emptyList(), emptyList(), emptyMap(), constraintsBlocked = true)
        }
    }

    /**
     * جولة مزامنة واحدة — معالجة كل العناصر الممررة ضد الدفترة والوجهة.
     * ترتيب الاستقرار: الأقدم أولاً (الكاميرا تُرفع بتسلسل زمني منطقي).
     */
    suspend fun sync(
        items: List<MediaItem>,
        target: CloudFs,
        policy: SyncPolicy,
        ledger: SyncLedger,
        environment: SyncEnvironment,
        maxItems: Int = 100,
        onProgress: (done: Int, total: Int, current: String) -> Unit = { _, _, _ -> },
    ): SyncReport {
        if (!policy.constraints.satisfied(environment)) return SyncReport.BLOCKED

        val uploaded = ArrayList<String>()
        val skipped = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()

        // الأقدم أولاً — تجربة متسقة مع ترتيب الكاميرا
        val ordered = items.sortedBy { it.lastModified }.take(maxItems)
        var done = 0

        for (item in ordered) {
            onProgress(done, ordered.size, item.name)
            // ═══ 1) هوية المحتوى — هاش متدفق (بلا حمل ذاكرة للفيديو الكبير) ═══
            val hashResult = runCatching {
                item.open().use { stream -> sha256Hex(stream) }
            }
            if (hashResult.isFailure) {
                failed[item.key] = "hash: ${hashResult.exceptionOrNull()?.message}"
                done++
                continue
            }
            val hash = hashResult.getOrThrow()

            // ═══ 2) exact-once: مطابقة الدفترة تخطّي بلا شبكة ═══
            val prior = ledger.get(item.key)
            if (prior != null && prior.contentHash == hash && prior.size == item.size) {
                skipped.add(item.key)
                done++
                continue
            }

            // ═══ 3) المسار الهدف وفق الاستراتيجية ═══
            val remoteDir = targetFolderFor(policy, item)
            if (remoteDir.isNotEmpty()) {
                val mkResult = runCatching { mkdirs(target, remoteDir) }
                if (mkResult.isFailure) {
                    failed[item.key] = "mkdir: ${mkResult.exceptionOrNull()?.message}"
                    done++
                    continue
                }
            }

            // ═══ 4) الرفع ثم الدفترة ═══
            try {
                val entry = target.writeFromStream(
                    dir = remoteDir,
                    name = item.name,
                    size = item.size,
                    openInput = item.open,
                )
                ledger.put(
                    LedgerRecord(
                        key = item.key,
                        contentHash = hash,
                        size = item.size,
                        remotePath = entry.path,
                        remoteRef = entry.etag ?: entry.remoteId,
                        uploadedAt = System.currentTimeMillis(),
                    ),
                )
                uploaded.add(item.key)
            } catch (e: Exception) {
                failed[item.key] = e.message ?: e.javaClass.simpleName
            }
            done++
        }
        onProgress(done, ordered.size, "")
        return SyncReport(uploaded, skipped, failed)
    }

    /** المجلد الهدف لكل عنصر — FLAT أو yyyy/MM (بتوقيت UTC للاستقرار)، ومجلد المصدر عند تعدد المصادر */
    fun targetFolderFor(policy: SyncPolicy, item: MediaItem): String {
        val base = policy.targetFolder.trimEnd('/')
        val withSource = if (policy.multiSource) {
            if (base.isBlank()) "/${item.source.folderLabel}" else "$base/${item.source.folderLabel}"
        } else {
            base
        }
        if (policy.naming == Naming.FLAT) return withSource
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            .apply { timeInMillis = item.lastModified }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val sub = "$y/${"%02d".format(m)}"
        return if (withSource.isBlank()) "/$sub" else "$withSource/$sub"
    }

    /** ينشئ سلسلة مجلدات على المزوّد — متسامح مع الوجود المسبق */
    private suspend fun mkdirs(target: CloudFs, dir: String) {
        val clean = dir.trim('/')
        if (clean.isEmpty()) return
        var parent = ""
        for (part in clean.split('/')) {
            val exists = runCatching { target.list(parent).any { it.isDir && it.name == part } }
                .getOrDefault(false)
            if (!exists) {
                runCatching { target.mkdir(parent, part) }
                    .onFailure { e ->
                        // تعارض الوجود المسبق مقبول (سباق جولتين متزامنتين)
                        if (!e.message.orEmpty().contains("exists", ignoreCase = true)) throw e
                    }
            }
            parent = join(parent, part)
        }
    }

    /** SHA-256 متدفق — hex صغير */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** يرشّح عناصر وسائط لمزامنة الكاميرا — صور وفيديو فقط */
    fun isSyncableMedia(name: String, isVideo: Boolean): Boolean {
        val n = name.lowercase()
        val imageExts = listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".heic", ".heif", ".bmp", ".dng")
        val videoExts = listOf(".mp4", ".mov", ".webm", ".3gp", ".mkv", ".avi", ".m4v")
        return if (isVideo) videoExts.any { n.endsWith(it) } else imageExts.any { n.endsWith(it) }
    }

    private fun join(parent: String, name: String): String =
        if (parent.isBlank() || parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"

    /** ملف محلي كمصدر وسائط — للاختبارات واستيراد يدوي */
    fun fromFile(file: File, isVideo: Boolean = false, key: String = file.absolutePath): MediaItem =
        MediaItem(
            key = key,
            name = file.name,
            size = file.length(),
            lastModified = file.lastModified(),
            isVideo = isVideo,
            open = { file.inputStream() },
        )
}

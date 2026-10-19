package com.novafiles.app.core.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.MediaStore
import com.novafiles.app.data.db.CloudAccountEntity
import com.novafiles.app.data.db.NovaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.16 — مدير مزامنة الكاميرا على أندرويد: يربط محرك [CameraSync] الخالص
 * بمصادر النظام:
 *  - MediaStore: صور/فيديوهات الكاميرا (أحدث من آخر مزامنة)
 *  - ConnectivityManager: هل الشبكة مقيسة (بيانات جوال)؟
 *  - BatteryManager: هل الجهاز يشحن؟ ونسبة البطارية
 *
 * القيود الافتراضية (Wi-Fi + شحن) تقيَّم هنا — المزامنة لا تبدأ خلاف ذلك.
 */
class CameraSyncManager(
    private val context: Context,
    private val db: NovaDb,
    private val accounts: CloudAccountManager,
    private val clientIds: (provider: String) -> String,
) {

    /** عنصر وسائط من MediaStore */
    data class MediaRow(
        val id: Long,
        val uri: String,
        val name: String,
        val size: Long,
        val dateModified: Long,
        val isVideo: Boolean,
    )

    /** فحص بيئة الجهاز الآن */
    fun environment(): CameraSync.SyncEnvironment {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val metered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val charging = bm.isCharging
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).let {
            if (it in 1..100) it else 100
        }
        return CameraSync.SyncEnvironment(
            networkMetered = metered,
            charging = charging,
            batteryPct = level,
        )
    }

    /**
     * مسح وسائط الكاميرا الجديدة — كل الصور/الفيديوهات المضافة بعد [sinceMs]
     * (بثواني Unix كما يعطي MediaStore)، مرتبة الأقدم أولاً.
     */
    suspend fun scanNewMedia(sinceMs: Long, limit: Int = 200): List<MediaRow> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<MediaRow>()
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
            )
            val sinceSec = sinceMs / 1000
            for (collection in listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )) {
                context.contentResolver.query(
                    collection,
                    projection,
                    "${MediaStore.MediaColumns.DATE_ADDED} > ?",
                    arrayOf(sinceSec.toString()),
                    "${MediaStore.MediaColumns.DATE_ADDED} ASC",
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val modCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (cursor.moveToNext() && out.size < limit) {
                        val name = cursor.getString(nameCol) ?: continue
                        val isVideo = collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        if (!CameraSync.isSyncableMedia(name, isVideo)) continue
                        out.add(
                            MediaRow(
                                id = cursor.getLong(idCol),
                                uri = collection.buildUpon().appendPath(cursor.getLong(idCol).toString()).build().toString(),
                                name = name,
                                size = cursor.getLong(sizeCol),
                                dateModified = cursor.getLong(modCol) * 1000,
                                isVideo = isVideo,
                            ),
                        )
                    }
                }
            }
            out
        }

    /** جولة مزامنة كاملة لحساب واحد — يعيد التقرير أو null عند الحجب/الغياب */
    suspend fun syncNow(account: CloudAccountEntity): CameraSync.SyncReport? =
        withContext(Dispatchers.IO) {
            if (!account.syncEnabled) return@withContext null
            val clientId = clientIds(account.provider)
            if (clientId.isBlank()) return@withContext null // بلا معرّف عميل مسجل

            val ledger = RoomLedger(db.syncLedgerDao())
            val env = environment()
            val constraints = CameraSync.SyncConstraints() // الافتراضي: Wi-Fi + شحن
            if (!constraints.satisfied(env)) return@withContext CameraSync.SyncReport.BLOCKED

            val provider = runCatching {
                accounts.providerFor(account, clientId)
            }.getOrNull() ?: return@withContext null

            val fs = (provider as CloudFsProvider).unwrap()
            val media = scanNewMedia(account.lastSyncAt)

            val items = media.map { row ->
                CameraSync.MediaItem(
                    key = row.uri,
                    name = row.name,
                    size = row.size,
                    lastModified = row.dateModified,
                    isVideo = row.isVideo,
                    open = {
                        context.contentResolver.openInputStream(android.net.Uri.parse(row.uri))
                            ?: throw java.io.IOException("cannot open $row.uri")
                    },
                )
            }

            val report = CameraSync.sync(
                items = items,
                target = fs,
                policy = CameraSync.SyncPolicy(
                    targetFolder = account.targetFolder,
                    naming = if (account.naming == "FLAT") CameraSync.Naming.FLAT else CameraSync.Naming.YEAR_MONTH,
                    constraints = constraints,
                ),
                ledger = ledger,
                environment = env,
            )
            provider.close()
            if (!report.constraintsBlocked && report.failed.isEmpty()) {
                // نحدّث عتبة «الجديد» إلى الآن — الفاشل يبقى للجولة القادمة
                db.cloudAccountsDao().touchSync(account.id, System.currentTimeMillis())
            }
            report
        }

    /** دفترة Room — جسر لـ CameraSync.SyncLedger */
    class RoomLedger(private val dao: com.novafiles.app.data.db.SyncLedgerDao) : CameraSync.SyncLedger {
        override suspend fun get(key: String): CameraSync.LedgerRecord? =
            dao.byKey(key)?.toRecord()

        override suspend fun put(record: CameraSync.LedgerRecord) {
            dao.put(
                com.novafiles.app.data.db.SyncLedgerEntity(
                    key = record.key,
                    contentHash = record.contentHash,
                    size = record.size,
                    remotePath = record.remotePath,
                    remoteRef = record.remoteRef,
                    uploadedAt = record.uploadedAt,
                ),
            )
        }

        override suspend fun all(): List<CameraSync.LedgerRecord> = dao.all().map { it.toRecord() }

        override suspend fun remove(key: String) = dao.remove(key)

        private fun com.novafiles.app.data.db.SyncLedgerEntity.toRecord() = CameraSync.LedgerRecord(
            key = key, contentHash = contentHash, size = size,
            remotePath = remotePath, remoteRef = remoteRef, uploadedAt = uploadedAt,
        )
    }
}

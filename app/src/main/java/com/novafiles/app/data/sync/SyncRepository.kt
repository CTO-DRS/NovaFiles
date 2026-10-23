package com.novafiles.app.data.sync

import android.content.Context
import com.novafiles.app.core.sync.SyncDirection
import com.novafiles.app.core.sync.SyncInterval
import com.novafiles.app.core.sync.SyncNode
import com.novafiles.app.core.sync.SyncRunStatus
import com.novafiles.app.core.sync.SyncScheduler
import com.novafiles.app.data.db.NovaDb
import com.novafiles.app.data.db.SyncPairEntity
import com.novafiles.app.data.db.SyncRunEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * v0.32.0 — مستودع أزواج المزامنة: طبقة رقيقة فوق غلاف Room تُطبّق
 * القواعد قبل أي كتابة (اسم صالح وفريد، مساران مختلفان، لا سحابة↔سحابة
 * في v0.32)، وتربط التفعيل/الحذف بجدولة WorkManager حتى لا يبقى زوج
 * معطّل مجدولاً ولا زوج محذوف يوقظ الجهاز.
 */
class SyncRepository(
    private val context: Context,
    private val db: NovaDb,
) {

    // ═══════════════ التحقق الخالص (قابل للاختبار دون أندرويد) ═══════════════

    /** نوع جانب الزوج */
    enum class SideType { LOCAL, CLOUD;

        companion object {
            fun fromName(name: String?): SideType =
                entries.firstOrNull { it.name == name } ?: LOCAL
        }
    }

    /** جانب زوج كامل للتحقق */
    data class Side(
        val type: SideType,
        val path: String,
        val accountId: Long = 0,
    )

    /** رموز أخطاء التحقق — تُترجم إلى سلاسل في الواجهة */
    sealed class ValidationError {
        data object BlankName : ValidationError()
        data object LongName : ValidationError()
        data object SameSide : ValidationError()
        data object CloudToCloud : ValidationError()
        data object EmptyCloudPath : ValidationError()
    }

    companion object {
        const val MAX_NAME = 60

        /** تحقق إنشاء/تعديل زوج — null = صالح */
        fun validate(name: String, source: Side, dest: Side): ValidationError? {
            if (name.isBlank()) return ValidationError.BlankName
            if (name.trim().length > MAX_NAME) return ValidationError.LongName
            if (source.type == SideType.CLOUD && source.path.isBlank()) return ValidationError.EmptyCloudPath
            if (dest.type == SideType.CLOUD && dest.path.isBlank()) return ValidationError.EmptyCloudPath
            // نفس الجانب تماماً = مزامنة الذات (يفحص أولاً — رسالة أدق)
            if (source.type == dest.type && source.path.trim() == dest.path.trim() && source.accountId == dest.accountId) {
                return ValidationError.SameSide
            }
            // سحابة↔سحابة غير مدعومة في v0.32 (قاعدة الخارطة: محلي↔سحابة أو محلي↔محلي)
            if (source.type == SideType.CLOUD && dest.type == SideType.CLOUD) return ValidationError.CloudToCloud
            return null
        }
    }

    // ═══════════════ القراءات ═══════════════

    fun observePairs(): Flow<List<SyncPairEntity>> = db.syncPairsDao().observeAll()

    fun observeRuns(pairId: Long): Flow<List<SyncRunEntity>> =
        db.syncRunsDao().observeRecent(pairId)

    suspend fun pair(id: Long): SyncPairEntity? = db.syncPairsDao().byId(id)

    /** هل الاسم مستخدم لزوج آخر؟ */
    suspend fun isNameTaken(name: String, exceptId: Long = 0): Boolean {
        val all = db.syncPairsDao().observeAll().first()
        return all.any { it.id != exceptId && it.name == name.trim() }
    }

    // ═══════════════ الكتابات ═══════════════

    /** إنشاء زوج — يعيد المعرف أو null عند فشل التحقق/تكرار الاسم */
    suspend fun create(pair: SyncPairEntity): Long? {
        val name = pair.name.trim()
        if (isNameTaken(name)) return null
        val id = runCatching { db.syncPairsDao().insert(pair.copy(name = name)) }.getOrNull() ?: return null
        reschedule(id)
        return id
    }

    /** تعديل زوج — يعيد false عند تكرار الاسم */
    suspend fun update(pair: SyncPairEntity): Boolean {
        val name = pair.name.trim()
        if (isNameTaken(name, exceptId = pair.id)) return false
        db.syncPairsDao().update(pair.copy(name = name))
        reschedule(pair.id)
        return true
    }

    /** حذف زوج مع جولاته وجدولته */
    suspend fun delete(id: Long) {
        SyncScheduler.cancelPeriodic(context, id)
        db.syncRunsDao().clearFor(id)
        db.syncPairsDao().remove(id)
    }

    /** تفعيل/تعطيل — يضبط الجدولة تبعاً لذلك */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        db.syncPairsDao().setEnabled(id, enabled)
        reschedule(id)
    }

    /** إعادة جدولة زوج حسب حالته الحالية (بعد إنشاء/تعديل/تفعيل) */
    suspend fun reschedule(id: Long) {
        val pair = db.syncPairsDao().byId(id) ?: return
        val interval = SyncInterval.fromName(pair.interval)
        if (!pair.enabled || interval == SyncInterval.MANUAL) {
            SyncScheduler.cancelPeriodic(context, id)
        } else {
            SyncScheduler.schedulePeriodic(
                context, id, interval,
                wifiOnly = pair.wifiOnly,
                requireCharging = pair.requireCharging,
            )
        }
    }

    /** تحديث نتيجة جولة: آخر تشغيل + أساس ثنائي الاتجاه + السجل الحلقي */
    suspend fun finishRun(
        pairId: Long,
        run: SyncRunEntity,
        report: com.novafiles.app.core.sync.PairRunReport,
        baseline: List<SyncNode>?,
    ) {
        db.syncRunsDao().update(run.copy(finishedAt = System.currentTimeMillis()))
        if (!report.cancelled) {
            db.syncPairsDao().touchRun(
                pairId,
                System.currentTimeMillis(),
                baseline?.let { com.novafiles.app.core.sync.SnapshotCodec.encode(it) },
            )
        }
        db.syncRunsDao().trim(pairId)
    }

    /** بدء سجل جولة جديدة — يعيد معرفه */
    suspend fun startRun(pairId: Long): SyncRunEntity {
        val entity = SyncRunEntity(
            pairId = pairId,
            startedAt = System.currentTimeMillis(),
            status = SyncRunStatus.RUNNING.name,
        )
        val id = db.syncRunsDao().insert(entity)
        return entity.copy(id = id)
    }
}

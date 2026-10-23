package com.novafiles.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** عنصر مفضلة: مسار ملف أو مجلد */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
)

// ═══════════════ v0.33 — الوسوم الملوّنة (تعريفات + إسناد) ═══════════════

/** تعريف وسم ملون — الاسم فريد (تطبيع TagsEngine قبل الحفظ) */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagDefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
)

/** إسناد وسم إلى مسار — PK مركّب يمنع التكرار */
@Entity(tableName = "tag_file", primaryKeys = ["tagId", "path"], indices = [Index("path")])
data class TagFileEntity(
    val tagId: Long,
    val path: String,
    val taggedAt: Long,
)

/** صف عدّ إجماليات وسم */
data class TagCountRow(val tagId: Long, val n: Int)

@Dao
interface FavoritesDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE path = :path)")
    fun observeIsFavorite(path: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun remove(path: String)

    @Query("DELETE FROM favorites WHERE path LIKE :prefix || '%'")
    suspend fun removeUnder(prefix: String)
}

@Dao
interface TagDefsDao {
    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<TagDefEntity>>

    @Query("SELECT * FROM tags ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<TagDefEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: Long): TagDefEntity?

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): TagDefEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(def: TagDefEntity): Long

    @Query("UPDATE tags SET colorIndex = :color WHERE id = :id")
    suspend fun setColor(id: Long, color: Int)

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("SELECT COUNT(*) FROM tags")
    suspend fun count(): Int
}

@Dao
interface TagFileDao {
    @Query("SELECT * FROM tag_file WHERE path = :path")
    fun observeForPath(path: String): Flow<List<TagFileEntity>>

    @Query("SELECT * FROM tag_file")
    fun observeAllLinks(): Flow<List<TagFileEntity>>

    @Query("SELECT * FROM tag_file")
    suspend fun all(): List<TagFileEntity>

    @Query("SELECT tagId FROM tag_file WHERE path = :path")
    suspend fun tagIdsForPath(path: String): List<Long>

    @Query("SELECT path FROM tag_file WHERE tagId = :tagId")
    suspend fun pathsForTag(tagId: Long): List<String>

    @Query("SELECT tagId AS tagId, COUNT(*) AS n FROM tag_file GROUP BY tagId")
    suspend fun counts(): List<TagCountRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(item: TagFileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkAll(items: List<TagFileEntity>)

    @Query("DELETE FROM tag_file WHERE tagId = :tagId AND path = :path")
    suspend fun unlink(tagId: Long, path: String)

    @Query("DELETE FROM tag_file WHERE path = :path")
    suspend fun untagPath(path: String)

    @Query("DELETE FROM tag_file WHERE tagId = :tagId")
    suspend fun untagAll(tagId: Long)
}

/** فهرس الملفات للبحث الفوري — صف لكل ملف/مجلد في التخزين */
@Entity(tableName = "file_index")
data class FileIndexEntity(
    @PrimaryKey val path: String,
    val name: String,
    val parent: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
)

/** جدول FTS4 مرادَف المضمون لعمود الاسم (بحث بادئة فوري) */
@Fts4(contentEntity = FileIndexEntity::class)
@Entity(tableName = "file_index_fts")
data class FileIndexFtsEntity(
    val name: String,
)

/** اتصال شبكة محفوظ (FTP أو SMB) لشاشة «الاتصال بالخوادم» */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String,      // "FTP" أو "SMB"
    val host: String,
    val port: Int,
    val domain: String = "",
    val username: String = "",
    val password: String = "",
    val share: String = "",    // SMB: اسم المشاركة
    val initialPath: String = "",
    val lastUsedAt: Long = 0L,
)

@Dao
interface ConnectionsDao {
    @Query("SELECT * FROM connections ORDER BY lastUsedAt DESC, id DESC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: ConnectionEntity): Long

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("UPDATE connections SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)
}

@Dao
interface FileIndexDao {
    @Query("SELECT COUNT(*) FROM file_index")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FileIndexEntity>)

    @Query("DELETE FROM file_index")
    suspend fun clearAll()

    /** بحث بادئة عبر FTS — أسرع خيار عند كتابة بداية اسم الملف */
    @Query(
        """SELECT file_index.* FROM file_index
        JOIN file_index_fts ON file_index.rowid = file_index_fts.docid
        WHERE file_index_fts MATCH :match
        ORDER BY file_index.modified DESC LIMIT :limit"""
    )
    suspend fun ftsMatch(match: String, limit: Int): List<FileIndexEntity>

    /** بحث احتياطي بـ LIKE لأي جزء داخل الاسم */
    @Query("SELECT * FROM file_index WHERE name LIKE '%' || :needle || '%' ORDER BY modified DESC LIMIT :limit")
    suspend fun likeMatch(needle: String, limit: Int): List<FileIndexEntity>

    /** كل الصفوف (للترشيح اليدوي عند البحث بلا نص) */
    @Query("SELECT * FROM file_index ORDER BY modified DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<FileIndexEntity>

    /** لقطة كاملة لتحليل التخزين (v0.14) — بلا حد عملياً حتى MAX_SCAN_FILES */
    @Query("SELECT * FROM file_index")
    suspend fun all(): List<FileIndexEntity>
}

// ═══════════════ v0.16 — الحسابات السحابية ودفترة مزامنة الكاميرا ═══════════════

/** حساب سحابي مُوثَّق (Drive/Dropbox/OneDrive) — التوكنات مشفرة في Keystore لا هنا */
@Entity(tableName = "cloud_accounts")
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** drive | dropbox | onedrive */
    val provider: String,
    /** اسم الحساب/البريد المعروض من الخدمة */
    val displayName: String,
    /** مجلد مزامنة الكاميرا الهدف */
    val targetFolder: String = "/NovaFiles-Camera",
    /** FLAT | YEAR_MONTH */
    val naming: String = "YEAR_MONTH",
    /** مزامنة الكاميرا مفعلة لهذا الحساب؟ */
    val syncEnabled: Boolean = false,
    val lastSyncAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
)

@Dao
interface CloudAccountsDao {
    @Query("SELECT * FROM cloud_accounts ORDER BY lastUsedAt DESC, id DESC")
    fun observeAll(): Flow<List<CloudAccountEntity>>

    @Query("SELECT * FROM cloud_accounts WHERE id = :id")
    suspend fun byId(id: Long): CloudAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: CloudAccountEntity): Long

    @Query("UPDATE cloud_accounts SET lastSyncAt = :ts WHERE id = :id")
    suspend fun touchSync(id: Long, ts: Long)

    @Query("UPDATE cloud_accounts SET lastUsedAt = :ts WHERE id = :id")
    suspend fun touchUsed(id: Long, ts: Long)

    @Query("DELETE FROM cloud_accounts WHERE id = :id")
    suspend fun remove(id: Long)
}

/** دفترة مزامنة الكاميرا — exact-once: سجل لكل عنصر وسائط بهوية محتواه */
@Entity(tableName = "sync_ledger")
data class SyncLedgerEntity(
    @PrimaryKey val key: String,
    val contentHash: String,
    val size: Long,
    val remotePath: String,
    val remoteRef: String?,
    val uploadedAt: Long,
)

@Dao
interface SyncLedgerDao {
    @Query("SELECT * FROM sync_ledger WHERE `key` = :key")
    suspend fun byKey(key: String): SyncLedgerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: SyncLedgerEntity)

    @Query("SELECT * FROM sync_ledger")
    suspend fun all(): List<SyncLedgerEntity>

    @Query("DELETE FROM sync_ledger WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT COUNT(*) FROM sync_ledger")
    suspend fun count(): Int
}

@Database(
    entities = [FavoriteEntity::class, TagDefEntity::class, TagFileEntity::class, FileIndexEntity::class, FileIndexFtsEntity::class, ConnectionEntity::class, CloudAccountEntity::class, SyncLedgerEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class NovaDb : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun tagDefsDao(): TagDefsDao
    abstract fun tagFileDao(): TagFileDao
    abstract fun fileIndexDao(): FileIndexDao
    abstract fun connectionsDao(): ConnectionsDao
    abstract fun cloudAccountsDao(): CloudAccountsDao
    abstract fun syncLedgerDao(): SyncLedgerDao

    companion object {
        /**
         * v0.33 — 4→5: تحويل جدول الوسوم القديم (path+tag مسطح) إلى
         * بنية v0.33 (تعريفات tag_defs-like في جدول tags + إسنادات tag_file)
         * مع نقل بيانات المستخدم القائمة دون فقدان.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) إبعاد الجدول القديم مؤقتاً
                db.execSQL("ALTER TABLE `tags` RENAME TO `tags_legacy_v4`")
                // 2) بنية v0.33 مطابقة تماماً لما تتوقعه Room
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag_file` (`tagId` INTEGER NOT NULL, `path` TEXT NOT NULL, " +
                        "`taggedAt` INTEGER NOT NULL, PRIMARY KEY(`tagId`, `path`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tag_file_path` ON `tag_file` (`path`)")
                // 3) نقل تعريفات الوسوم المميزة مع لون حتمي من الاسم (FNV-1a)
                val now = System.currentTimeMillis()
                val defIds = HashMap<String, Long>() // canonical ← id
                db.query("SELECT DISTINCT tag FROM `tags_legacy_v4` ORDER BY tag").use { c ->
                    while (c.moveToNext()) {
                        val legacy = c.getString(0) ?: continue
                        val name = com.novafiles.app.core.tags.TagsEngine.normalize(legacy) ?: continue
                        val key = name.lowercase()
                        if (defIds.containsKey(key)) continue
                        db.execSQL(
                            "INSERT OR IGNORE INTO `tags` (`name`, `colorIndex`, `createdAt`) VALUES (?, ?, ?)",
                            arrayOf<Any>(name, com.novafiles.app.core.tags.TagPalette.colorFor(key), now),
                        )
                    }
                }
                db.query("SELECT `id`, `name` FROM `tags`").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        defIds[name.lowercase()] = id
                    }
                }
                // 4) نقل الإسنادات بالربط بالاسم المطبَّع (PK مركّب يتجاهل التكرار)
                db.query("SELECT `path`, `tag` FROM `tags_legacy_v4`").use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(0) ?: continue
                        val legacy = c.getString(1) ?: continue
                        val key = com.novafiles.app.core.tags.TagsEngine.normalize(legacy)?.lowercase() ?: continue
                        val tagId = defIds[key] ?: continue
                        db.execSQL(
                            "INSERT OR IGNORE INTO `tag_file` (`tagId`, `path`, `taggedAt`) VALUES (?, ?, ?)",
                            arrayOf<Any>(tagId, path, now),
                        )
                    }
                }
                // 5) إسقاط الجدول القديم
                db.execSQL("DROP TABLE IF EXISTS `tags_legacy_v4`")
            }
        }
    }
}

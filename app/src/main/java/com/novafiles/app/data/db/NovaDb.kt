package com.novafiles.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** عنصر مفضلة: مسار ملف أو مجلد */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val path: String,
    val name: String,
    val isDirectory: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
)

/** وسم مخصص على مسار (توسعة مستقبلية للوسوم التلقائية) */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val tag: String,
)

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
interface TagsDao {
    @Query("SELECT * FROM tags WHERE path = :path")
    fun observeFor(path: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE tag LIKE '%' || :query || '%'")
    suspend fun findByTag(query: String): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun remove(id: Long)
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
}

@Database(
    entities = [FavoriteEntity::class, TagEntity::class, FileIndexEntity::class, FileIndexFtsEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class NovaDb : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun tagsDao(): TagsDao
    abstract fun fileIndexDao(): FileIndexDao
}

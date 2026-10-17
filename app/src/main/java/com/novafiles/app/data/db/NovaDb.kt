package com.novafiles.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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

@Database(entities = [FavoriteEntity::class, TagEntity::class], version = 1, exportSchema = false)
abstract class NovaDb : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun tagsDao(): TagsDao
}

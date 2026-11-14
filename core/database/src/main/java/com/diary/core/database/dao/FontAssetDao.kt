package com.diary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diary.core.database.entity.FontAssetEntity
import kotlinx.coroutines.flow.Flow

/**
 * 字体元信息表访问入口。内置字体排在前，用户导入字体按导入时间升序。
 */
@Dao
interface FontAssetDao {
    @Query("SELECT * FROM font_asset ORDER BY source ASC, imported_at ASC, id ASC")
    fun observeAll(): Flow<List<FontAssetEntity>>

    @Query("SELECT * FROM font_asset ORDER BY source ASC, imported_at ASC, id ASC")
    suspend fun getAll(): List<FontAssetEntity>

    @Query("SELECT * FROM font_asset WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FontAssetEntity?

    @Query("SELECT COUNT(*) FROM font_asset WHERE source = 1")
    suspend fun countImported(): Int

    @Insert
    suspend fun insert(entity: FontAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FontAssetEntity>)

    @Query("DELETE FROM font_asset")
    suspend fun clearAll()

    @Query("UPDATE font_asset SET display_name = :displayName WHERE id = :id")
    suspend fun rename(
        id: Long,
        displayName: String,
    )

    @Query("UPDATE font_asset SET status = :status WHERE id = :id")
    suspend fun updateStatus(
        id: Long,
        status: Int,
    )

    @Query("DELETE FROM font_asset WHERE id = :id")
    suspend fun deleteById(id: Long): Int
}

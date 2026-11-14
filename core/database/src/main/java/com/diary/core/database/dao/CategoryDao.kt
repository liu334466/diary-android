package com.diary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diary.core.database.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 分类夹表访问入口。排序规则：系统分类置顶，其余按 sort_order、id 升序。
 */
@Dao
interface CategoryDao {
    @Query("SELECT * FROM category ORDER BY is_system DESC, sort_order ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY is_system DESC, sort_order ASC, id ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM category WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM category WHERE is_system = 0")
    suspend fun countCustom(): Int

    @Query("SELECT COUNT(*) FROM category WHERE is_system = 0 AND name = :name AND id != :excludeId")
    suspend fun countCustomByName(
        name: String,
        excludeId: Long,
    ): Int

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM category WHERE is_system = 0")
    suspend fun maxCustomSortOrder(): Int

    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Query("DELETE FROM category")
    suspend fun clearAll()

    @Query("UPDATE category SET name = :name WHERE id = :id AND is_system = 0")
    suspend fun rename(
        id: Long,
        name: String,
    ): Int

    @Query("UPDATE category SET sort_order = :sortOrder WHERE id = :id AND is_system = 0")
    suspend fun updateSortOrder(
        id: Long,
        sortOrder: Int,
    )

    @Query("DELETE FROM category WHERE id = :id AND is_system = 0")
    suspend fun deleteById(id: Long): Int
}

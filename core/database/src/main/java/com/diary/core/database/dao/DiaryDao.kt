package com.diary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diary.core.database.entity.DiaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * 分类维度日记数量，用于分类页与首页标签展示。
 */
data class CategoryCount(
    val categoryId: Long,
    val count: Int,
)

/**
 * 字体引用数量，用于字体管理页提示删除影响面。
 */
data class FontRefCount(
    val fontId: Long,
    val count: Int,
)

/**
 * 日记表访问入口。所有列表查询默认过滤软删除记录，回收站单独查询。
 */
@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun observeActive(): Flow<List<DiaryEntity>>

    @Query(
        """
        SELECT * FROM diary
        WHERE deleted_at IS NULL AND category_id = :categoryId
        ORDER BY created_at DESC
        """,
    )
    fun observeActiveByCategory(categoryId: Long): Flow<List<DiaryEntity>>

    @Query("SELECT * FROM diary WHERE deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeTrash(): Flow<List<DiaryEntity>>

    /** 日历用：按创建时间取一段区间内的日记，区间为左闭右开。 */
    @Query(
        """
        SELECT * FROM diary
        WHERE deleted_at IS NULL AND created_at >= :start AND created_at < :end
        ORDER BY created_at ASC
        """,
    )
    fun observeActiveBetween(
        start: Long,
        end: Long,
    ): Flow<List<DiaryEntity>>

    @Query("SELECT COUNT(*) FROM diary WHERE deleted_at IS NOT NULL")
    fun observeTrashCount(): Flow<Int>

    @Query(
        """
        SELECT category_id AS categoryId, COUNT(*) AS count FROM diary
        WHERE deleted_at IS NULL
        GROUP BY category_id
        """,
    )
    fun observeActiveCounts(): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT font_id AS fontId, COUNT(*) AS count FROM diary
        WHERE deleted_at IS NULL AND font_id IS NOT NULL
        GROUP BY font_id
        """,
    )
    fun observeFontRefCounts(): Flow<List<FontRefCount>>

    @Query("SELECT * FROM diary WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DiaryEntity?

    /** 备份用：包含回收站在内的全部日记。 */
    @Query("SELECT * FROM diary ORDER BY id ASC")
    suspend fun getAll(): List<DiaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<DiaryEntity>)

    /** 恢复备份前清空日记表，关联标签由外键级联清理。 */
    @Query("DELETE FROM diary")
    suspend fun clearAll()

    @Insert
    suspend fun insert(entity: DiaryEntity): Long

    @Query(
        """
        UPDATE diary
        SET title = :title,
            content_json = :contentJson,
            plain_text = :plainText,
            char_count = :charCount,
            updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateContent(
        id: Long,
        title: String?,
        contentJson: String,
        plainText: String,
        charCount: Int,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE diary
        SET font_id = :fontId, font_size_level = :fontSizeLevel, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateFont(
        id: Long,
        fontId: Long?,
        fontSizeLevel: Int?,
        updatedAt: Long,
    )

    @Query("UPDATE diary SET category_id = :categoryId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCategory(
        id: Long,
        categoryId: Long,
        updatedAt: Long,
    )

    @Query("UPDATE diary SET deleted_at = :deletedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateDeletedAt(
        id: Long,
        deletedAt: Long?,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE diary
        SET deleted_at = NULL, category_id = :categoryId, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun restore(
        id: Long,
        categoryId: Long,
        updatedAt: Long,
    )

    @Query("DELETE FROM diary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM diary")
    suspend fun countAll(): Int

    @Query("SELECT id FROM diary WHERE deleted_at IS NOT NULL")
    suspend fun trashIds(): List<Long>

    @Query("SELECT id FROM diary WHERE deleted_at IS NOT NULL AND deleted_at <= :threshold")
    suspend fun expiredIds(threshold: Long): List<Long>

    @Query("DELETE FROM diary WHERE deleted_at IS NOT NULL")
    suspend fun clearTrash()

    @Query("DELETE FROM diary WHERE deleted_at IS NOT NULL AND deleted_at <= :threshold")
    suspend fun purgeExpired(threshold: Long): Int

    @Query("UPDATE diary SET category_id = :targetCategoryId WHERE category_id = :sourceCategoryId")
    suspend fun migrateCategory(
        sourceCategoryId: Long,
        targetCategoryId: Long,
    )

    @Query("UPDATE diary SET font_id = NULL WHERE font_id = :fontId")
    suspend fun clearFontReference(fontId: Long)

    @Query(
        """
        SELECT * FROM diary
        WHERE font_id = :fontId OR content_json LIKE :contentPattern
        """,
    )
    suspend fun findReferencingFont(
        fontId: Long,
        contentPattern: String,
    ): List<DiaryEntity>

    @Query("UPDATE diary SET content_json = :contentJson WHERE id = :id")
    suspend fun updateContentJson(
        id: Long,
        contentJson: String,
    )
}

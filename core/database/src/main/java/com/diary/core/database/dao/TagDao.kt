package com.diary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryTagEntity
import com.diary.core.database.entity.DiaryTagLink
import com.diary.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/**
 * 标签使用量，用于标签管理页展示影响面。
 */
data class TagUsage(
    val tagId: Long,
    val count: Int,
)

/**
 * 标签与日记-标签关联的读写入口。
 */
@Dao
interface TagDao {
    @Query("SELECT * FROM tag ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT tag_id AS tagId, COUNT(*) AS count FROM diary_tag GROUP BY tag_id")
    fun observeUsage(): Flow<List<TagUsage>>

    @Query("UPDATE tag SET name = :name WHERE id = :id")
    suspend fun updateName(
        id: Long,
        name: String,
    )

    @Query("SELECT * FROM tag ORDER BY created_at ASC, id ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tag WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tag WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT COUNT(*) FROM tag")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: TagEntity): Long

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT diary_id AS diaryId, tag_id AS tagId FROM diary_tag")
    fun observeLinks(): Flow<List<DiaryTagLink>>

    @Query("SELECT diary_id AS diaryId, tag_id AS tagId FROM diary_tag")
    suspend fun getLinks(): List<DiaryTagLink>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Query("DELETE FROM diary_tag")
    suspend fun clearAllLinks()

    @Query("DELETE FROM tag")
    suspend fun clearAllTags()

    @Query("SELECT tag_id FROM diary_tag WHERE diary_id = :diaryId ORDER BY tag_id ASC")
    suspend fun tagIdsOf(diaryId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<DiaryTagEntity>)

    @Query("DELETE FROM diary_tag WHERE diary_id = :diaryId")
    suspend fun clearLinks(diaryId: Long)

    @Query("SELECT COUNT(*) FROM diary_tag WHERE tag_id = :tagId")
    suspend fun tagUsage(tagId: Long): Int

    /**
     * 按标签取日记，仅返回未删除记录，供搜索页标签筛选使用。
     */
    @Query(
        """
        SELECT d.* FROM diary d
        INNER JOIN diary_tag dt ON dt.diary_id = d.id
        WHERE dt.tag_id = :tagId AND d.deleted_at IS NULL
        ORDER BY d.updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun diariesWithTag(
        tagId: Long,
        limit: Int,
    ): List<DiaryEntity>
}

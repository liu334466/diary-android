package com.diary.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryFtsEntity
import com.diary.core.database.entity.DiaryIndexRow

/**
 * 全文索引表的读写入口。索引内容由 Repository 维护，DAO 只负责存取与检索。
 */
@Dao
interface DiaryFtsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DiaryFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DiaryFtsEntity>)

    @Query("DELETE FROM diary_fts WHERE rowid = :rowId")
    suspend fun delete(rowId: Long)

    @Query("DELETE FROM diary_fts WHERE rowid IN (:rowIds)")
    suspend fun deleteAll(rowIds: List<Long>)

    @Query("DELETE FROM diary_fts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM diary_fts")
    suspend fun count(): Int

    @Query("SELECT id, title, plain_text AS plainText FROM diary")
    suspend fun indexRows(): List<DiaryIndexRow>

    /**
     * FTS 粗筛：先取 rowid 再回表，避免 JOIN 让查询规划器走全表扫描。
     */
    @Query(
        """
        SELECT * FROM diary
        WHERE deleted_at IS NULL
          AND id IN (SELECT rowid FROM diary_fts WHERE diary_fts MATCH :match)
        ORDER BY updated_at DESC
        LIMIT :limit
        """,
    )
    suspend fun search(
        match: String,
        limit: Int,
    ): List<DiaryEntity>
}

package com.diary.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * 日记全文索引。行号与 diary.id 一一对应，内容为 [com.diary.core.database.search.SearchTokenizer]
 * 生成的 token 串（汉字单字 + 双字），由 Repository 在写入日记时同步维护。
 *
 * 索引不参与软删除：回收站中的日记仍可被搜到但不出现在结果里，恢复后无需重建。
 */
@Entity(tableName = "diary_fts")
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class DiaryFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "title_tokens")
    val titleTokens: String,
    @ColumnInfo(name = "body_tokens")
    val bodyTokens: String,
)

/**
 * 重建索引时需要的日记字段快照。
 */
data class DiaryIndexRow(
    val id: Long,
    val title: String?,
    val plainText: String,
)

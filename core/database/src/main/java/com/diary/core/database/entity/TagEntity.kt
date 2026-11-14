package com.diary.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.diary.core.model.Tag

/**
 * 标签表，名称全局唯一，便于跨日记复用与筛选。
 */
@Entity(
    tableName = "tag",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 日记与标签的多对多关联。物理删除日记或标签时由外键级联清理。
 */
@Entity(
    tableName = "diary_tag",
    primaryKeys = ["diary_id", "tag_id"],
    indices = [Index(value = ["tag_id"])],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntity::class,
            parentColumns = ["id"],
            childColumns = ["diary_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DiaryTagEntity(
    @ColumnInfo(name = "diary_id")
    val diaryId: Long,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)

/**
 * 关联表查询投影，用于一次性取回全部映射。
 */
data class DiaryTagLink(
    val diaryId: Long,
    val tagId: Long,
)

fun TagEntity.toModel(): Tag =
    Tag(
        id = id,
        name = name,
        createdAt = createdAt,
    )

fun Tag.toEntity(): TagEntity =
    TagEntity(
        id = id,
        name = name,
        createdAt = createdAt,
    )

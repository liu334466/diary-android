package com.diary.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.diary.core.model.Category
import com.diary.core.model.Diary
import com.diary.core.model.FontAsset

/**
 * 日记表，字段与 PRD 4.2 一一对应。
 * 列表按分类 + 创建时间倒序查询，回收站按删除时间倒序查询。
 */
@Entity(
    tableName = "diary",
    indices = [
        Index(value = ["category_id", "created_at"]),
        Index(value = ["deleted_at"]),
    ],
)
data class DiaryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "title")
    val title: String? = null,
    @ColumnInfo(name = "content_json")
    val contentJson: String = "",
    @ColumnInfo(name = "plain_text")
    val plainText: String = "",
    @ColumnInfo(name = "category_id")
    val categoryId: Long,
    @ColumnInfo(name = "font_id")
    val fontId: Long? = null,
    @ColumnInfo(name = "font_size_level")
    val fontSizeLevel: Int? = null,
    @ColumnInfo(name = "char_count")
    val charCount: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
)

/**
 * 分类夹表，id=1 的系统分类“未分类”由数据库创建回调写入。
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["sort_order"])],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "is_system")
    val isSystem: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * 字体元信息表，文件本体存放于应用私有目录。
 */
@Entity(
    tableName = "font_asset",
    indices = [Index(value = ["file_name"], unique = true)],
)
data class FontAssetEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0L,
    @ColumnInfo(name = "source")
    val source: Int = FontAsset.SOURCE_IMPORTED,
    @ColumnInfo(name = "status")
    val status: Int = FontAsset.STATUS_OK,
    @ColumnInfo(name = "imported_at")
    val importedAt: Long,
)

fun DiaryEntity.toModel(): Diary =
    Diary(
        id = id,
        title = title,
        contentJson = contentJson,
        plainText = plainText,
        categoryId = categoryId,
        fontId = fontId,
        fontSizeLevel = fontSizeLevel,
        charCount = charCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun Diary.toEntity(): DiaryEntity =
    DiaryEntity(
        id = id,
        title = title,
        contentJson = contentJson,
        plainText = plainText,
        categoryId = categoryId,
        fontId = fontId,
        fontSizeLevel = fontSizeLevel,
        charCount = charCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

fun CategoryEntity.toModel(): Category =
    Category(
        id = id,
        name = name,
        sortOrder = sortOrder,
        isSystem = isSystem,
        createdAt = createdAt,
    )

fun Category.toEntity(): CategoryEntity =
    CategoryEntity(
        id = id,
        name = name,
        sortOrder = sortOrder,
        isSystem = isSystem,
        createdAt = createdAt,
    )

fun FontAssetEntity.toModel(): FontAsset =
    FontAsset(
        id = id,
        displayName = displayName,
        fileName = fileName,
        fileSize = fileSize,
        source = source,
        status = status,
        importedAt = importedAt,
    )

fun FontAsset.toEntity(): FontAssetEntity =
    FontAssetEntity(
        id = id,
        displayName = displayName,
        fileName = fileName,
        fileSize = fileSize,
        source = source,
        status = status,
        importedAt = importedAt,
    )

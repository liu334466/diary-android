package com.diary.core.model

/**
 * 日记领域模型，字段与 PRD 4.2 保持一致。
 */
data class Diary(
    val id: Long = 0L,
    val title: String? = null,
    val contentJson: String = "",
    val plainText: String = "",
    val categoryId: Long = 1L,
    val fontId: Long? = null,
    val fontSizeLevel: Int? = null,
    val charCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
)

/**
 * 分类夹领域模型，未分类为系统分类，固定 id 为 1。
 */
data class Category(
    val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0,
    val isSystem: Boolean = false,
    val createdAt: Long,
)

/**
 * 标签领域模型，名称全局唯一。
 */
data class Tag(
    val id: Long = 0L,
    val name: String,
    val createdAt: Long,
)

/**
 * 字体领域模型，source 为 0 表示内置，1 表示用户导入。
 */
data class FontAsset(
    val id: Long = 0L,
    val displayName: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val source: Int = SOURCE_BUILT_IN,
    val status: Int = STATUS_OK,
    val importedAt: Long,
) {
    companion object {
        const val SOURCE_BUILT_IN = 0
        const val SOURCE_IMPORTED = 1

        const val STATUS_OK = 0
        const val STATUS_INVALID = 1
    }
}

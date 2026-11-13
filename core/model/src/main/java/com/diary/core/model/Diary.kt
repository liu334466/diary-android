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
    val deletedAt: Long? = null
)

/**
 * 分类夹领域模型，未分类为系统分类，固定 id 为 1。
 */
data class Category(
    val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0,
    val isSystem: Boolean = false,
    val createdAt: Long
)

/**
 * 字体领域模型，source 为 0 表示内置，1 表示用户导入。
 */
data class FontAsset(
    val id: Long = 0L,
    val displayName: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val source: Int = 0,
    val status: Int = 0,
    val importedAt: Long
)

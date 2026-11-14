package com.diary.core.database.repository

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.CategoryDao
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.DiaryFtsDao
import com.diary.core.database.dao.FontAssetDao
import com.diary.core.database.dao.TagDao
import com.diary.core.database.entity.CategoryEntity
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryFtsEntity
import com.diary.core.database.entity.DiaryTagEntity
import com.diary.core.database.entity.DiaryTagLink
import com.diary.core.database.entity.FontAssetEntity
import com.diary.core.database.entity.TagEntity
import com.diary.core.database.search.SearchTokenizer
import com.diary.core.model.DiaryLimits
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地备份的完整数据快照，含回收站日记与全部关联。
 */
data class DiaryBackupContent(
    val categories: List<CategoryEntity> = emptyList(),
    val diaries: List<DiaryEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tagLinks: List<DiaryTagLink> = emptyList(),
    val fonts: List<FontAssetEntity> = emptyList(),
)

/**
 * 备份与恢复的数据侧入口：读取全量快照，或整体覆盖写入并重建全文索引。
 * 文件的打包解包由 `core:backup` 负责，这里只处理数据库事务。
 */
@Singleton
class BackupRepository
    @Inject
    constructor(
        private val diaryDao: DiaryDao,
        private val categoryDao: CategoryDao,
        private val fontAssetDao: FontAssetDao,
        private val tagDao: TagDao,
        private val diaryFtsDao: DiaryFtsDao,
        private val database: DiaryDatabase,
    ) {
        suspend fun snapshot(): DiaryBackupContent =
            DiaryBackupContent(
                categories = categoryDao.getAll(),
                diaries = diaryDao.getAll(),
                tags = tagDao.getAll(),
                tagLinks = tagDao.getLinks(),
                fonts = fontAssetDao.getAll(),
            )

        /**
         * 覆盖式恢复：清空现有内容后整体替换，并立即重建全文索引。
         *
         * 备份中缺少系统分类时补回“未分类”；日记引用了不存在的分类时回退到未分类；
         * 关联表中指向不存在日记或标签的记录会被丢弃，避免外键约束失败。
         */
        suspend fun replaceAll(content: DiaryBackupContent) {
            val categories = content.categories.toMutableList()
            if (categories.none { it.id == DiaryLimits.UNCATEGORIZED_ID }) {
                categories +=
                    CategoryEntity(
                        id = DiaryLimits.UNCATEGORIZED_ID,
                        name = DiaryLimits.UNCATEGORIZED_NAME,
                        sortOrder = 0,
                        isSystem = true,
                        createdAt =
                            content.diaries.minOfOrNull { it.createdAt }
                                ?: System.currentTimeMillis(),
                    )
            }
            val categoryIds = categories.mapTo(HashSet()) { it.id }
            val diaries =
                content.diaries.map { diary ->
                    if (diary.categoryId in categoryIds) {
                        diary
                    } else {
                        diary.copy(categoryId = DiaryLimits.UNCATEGORIZED_ID)
                    }
                }
            val diaryIds = diaries.mapTo(HashSet()) { it.id }
            val tagIds = content.tags.mapTo(HashSet()) { it.id }
            val links =
                content.tagLinks
                    .filter { it.diaryId in diaryIds && it.tagId in tagIds }
                    .distinctBy { it.diaryId to it.tagId }

            database.withTransaction {
                tagDao.clearAllLinks()
                tagDao.clearAllTags()
                diaryFtsDao.clear()
                diaryDao.clearAll()
                fontAssetDao.clearAll()
                categoryDao.clearAll()

                categoryDao.insertAll(categories)
                diaryDao.insertAll(diaries)
                if (content.tags.isNotEmpty()) tagDao.insertTags(content.tags)
                if (links.isNotEmpty()) {
                    tagDao.insertLinks(links.map { DiaryTagEntity(it.diaryId, it.tagId) })
                }
                if (content.fonts.isNotEmpty()) fontAssetDao.insertAll(content.fonts)
                if (diaries.isNotEmpty()) {
                    diaryFtsDao.upsertAll(
                        diaries.map { diary ->
                            DiaryFtsEntity(
                                rowId = diary.id,
                                titleTokens = SearchTokenizer.indexOf(diary.title),
                                bodyTokens = SearchTokenizer.indexOf(diary.plainText),
                            )
                        },
                    )
                }
            }
        }
    }

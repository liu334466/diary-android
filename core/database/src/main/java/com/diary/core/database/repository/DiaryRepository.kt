package com.diary.core.database.repository

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.CategoryDao
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.DiaryFtsDao
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryFtsEntity
import com.diary.core.database.entity.toModel
import com.diary.core.database.search.SearchTokenizer
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import com.diary.core.richtext.RichTextCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日记数据入口，列表默认过滤软删除记录，回收站单独提供查询。
 * 写入与删除时同步维护全文索引，保证搜索结果与正文一致。
 */
@Singleton
class DiaryRepository
    @Inject
    constructor(
        private val diaryDao: DiaryDao,
        private val categoryDao: CategoryDao,
        private val diaryFtsDao: DiaryFtsDao,
        private val database: DiaryDatabase,
    ) {
        fun observeActive(): Flow<List<Diary>> = diaryDao.observeActive().map { list -> list.map(DiaryEntity::toModel) }

        fun observeActiveByCategory(categoryId: Long): Flow<List<Diary>> =
            diaryDao.observeActiveByCategory(categoryId).map { list -> list.map(DiaryEntity::toModel) }

        fun observeTrash(): Flow<List<Diary>> = diaryDao.observeTrash().map { list -> list.map(DiaryEntity::toModel) }

        /** 日历用：按创建时间区间取日记。 */
        fun observeBetween(
            start: Long,
            end: Long,
        ): Flow<List<Diary>> = diaryDao.observeActiveBetween(start, end).map { list -> list.map(DiaryEntity::toModel) }

        fun observeTrashCount(): Flow<Int> = diaryDao.observeTrashCount()

        fun observeActiveCounts(): Flow<Map<Long, Int>> =
            diaryDao.observeActiveCounts().map { rows ->
                rows.associate { it.categoryId to it.count }
            }

        fun observeFontRefCounts(): Flow<Map<Long, Int>> =
            diaryDao.observeFontRefCounts().map { rows ->
                rows.associate { it.fontId to it.count }
            }

        suspend fun getById(id: Long): Diary? = diaryDao.getById(id)?.toModel()

        /**
         * 新建空白日记，默认归入“未分类”；分类不存在时回退到未分类。
         */
        suspend fun create(
            categoryId: Long = DiaryLimits.UNCATEGORIZED_ID,
            now: Long = System.currentTimeMillis(),
        ): Long {
            val targetCategoryId =
                categoryId
                    .takeIf { categoryDao.getById(it) != null }
                    ?: DiaryLimits.UNCATEGORIZED_ID
            val id =
                diaryDao.insert(
                    DiaryEntity(
                        title = null,
                        contentJson = RichTextCodec.emptyJson(),
                        plainText = "",
                        categoryId = targetCategoryId,
                        charCount = 0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            diaryFtsDao.upsert(
                DiaryFtsEntity(
                    rowId = id,
                    titleTokens = SearchTokenizer.indexOf(null),
                    bodyTokens = SearchTokenizer.indexOf(""),
                ),
            )
            return id
        }

        /**
         * 保存正文，同时刷新纯文本副本、字数、更新时间与全文索引。
         */
        suspend fun saveContent(
            id: Long,
            title: String?,
            contentJson: String,
            now: Long = System.currentTimeMillis(),
        ) {
            val document = RichTextCodec.decode(contentJson)
            val plainText = RichTextCodec.plainText(document)
            val safeTitle = sanitizeTitle(title)
            database.withTransaction {
                diaryDao.updateContent(
                    id = id,
                    title = safeTitle,
                    contentJson = contentJson,
                    plainText = plainText,
                    charCount = RichTextCodec.charCount(plainText),
                    updatedAt = now,
                )
                diaryFtsDao.upsert(
                    DiaryFtsEntity(
                        rowId = id,
                        titleTokens = SearchTokenizer.indexOf(safeTitle),
                        bodyTokens = SearchTokenizer.indexOf(plainText),
                    ),
                )
            }
        }

        /**
         * 全文搜索：FTS 粗筛后按原文包含关系精确过滤，仅返回未删除的日记。
         *
         * @param limit 返回条数上限，粗筛阶段会多取若干条以抵消误命中
         */
        suspend fun search(
            raw: String,
            limit: Int = SEARCH_LIMIT,
        ): List<Diary> {
            val match = SearchTokenizer.matchQuery(raw) ?: return emptyList()
            val terms = SearchTokenizer.termsOf(raw)
            if (terms.isEmpty()) return emptyList()
            val candidates = diaryFtsDao.search(match, limit * 3)
            return candidates.asSequence()
                .map(DiaryEntity::toModel)
                .filter { diary ->
                    val haystack =
                        ((diary.title ?: "") + "\n" + diary.plainText)
                            .lowercase(Locale.ROOT)
                    terms.all { haystack.contains(it) }
                }
                .take(limit)
                .toList()
        }

        suspend fun updateFont(
            id: Long,
            fontId: Long?,
            fontSizeLevel: Int?,
            now: Long = System.currentTimeMillis(),
        ) {
            val level = fontSizeLevel?.takeIf { it in 0 until DiaryLimits.FONT_SIZE_LEVEL_COUNT }
            diaryDao.updateFont(id, fontId, level, now)
        }

        suspend fun moveToCategory(
            id: Long,
            categoryId: Long,
            now: Long = System.currentTimeMillis(),
        ) {
            val target =
                categoryId
                    .takeIf { categoryDao.getById(it) != null }
                    ?: DiaryLimits.UNCATEGORIZED_ID
            diaryDao.updateCategory(id, target, now)
        }

        /**
         * 软删除：仅写入 deleted_at，内容、分类与字体设置全部保留。
         */
        suspend fun softDelete(
            id: Long,
            now: Long = System.currentTimeMillis(),
        ) {
            diaryDao.updateDeletedAt(id, now, now)
        }

        /**
         * 恢复日记；原分类已被删除时归入“未分类”。
         */
        suspend fun restore(
            id: Long,
            now: Long = System.currentTimeMillis(),
        ): Boolean {
            val diary = diaryDao.getById(id) ?: return false
            if (diary.deletedAt == null) return false
            val categoryId =
                diary.categoryId
                    .takeIf { categoryDao.getById(it) != null }
                    ?: DiaryLimits.UNCATEGORIZED_ID
            diaryDao.restore(id, categoryId, now)
            return true
        }

        suspend fun deletePermanently(id: Long) {
            database.withTransaction {
                diaryFtsDao.delete(id)
                diaryDao.deleteById(id)
            }
        }

        suspend fun clearTrash() {
            database.withTransaction {
                val ids = diaryDao.trashIds()
                if (ids.isNotEmpty()) diaryFtsDao.deleteAll(ids)
                diaryDao.clearTrash()
            }
        }

        /**
         * 清理超过 30 天的回收站记录，返回物理删除数量。
         */
        suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int {
            val threshold = now - DiaryLimits.TRASH_RETENTION_MILLIS
            return database.withTransaction {
                val ids = diaryDao.expiredIds(threshold)
                if (ids.isNotEmpty()) diaryFtsDao.deleteAll(ids)
                diaryDao.purgeExpired(threshold)
            }
        }

        /**
         * 回收站剩余天数，向上取整；已过期或未删除返回 0。
         */
        fun trashRemainingDays(
            deletedAt: Long?,
            now: Long = System.currentTimeMillis(),
        ): Int {
            if (deletedAt == null) return 0
            val remaining = deletedAt + DiaryLimits.TRASH_RETENTION_MILLIS - now
            if (remaining <= 0L) return 0
            return kotlin.math.ceil(remaining.toDouble() / DAY_MILLIS).toInt()
        }

        private fun sanitizeTitle(title: String?): String? {
            val trimmed = title?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            return trimmed.take(DiaryLimits.MAX_TITLE_LENGTH)
        }

        private companion object {
            const val DAY_MILLIS = 24L * 60L * 60L * 1000L

            /** 搜索结果默认上限。 */
            const val SEARCH_LIMIT = 100
        }
    }

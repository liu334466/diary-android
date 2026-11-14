package com.diary.core.database.repository

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.TagDao
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryTagEntity
import com.diary.core.database.entity.TagEntity
import com.diary.core.database.entity.toModel
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import com.diary.core.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 标签数据入口：标签库维护、单篇标签绑定与按标签筛选。
 */
@Singleton
class TagRepository
    @Inject
    constructor(
        private val tagDao: TagDao,
        private val database: DiaryDatabase,
    ) {
        fun observeAll(): Flow<List<Tag>> = tagDao.observeAll().map { list -> list.map(TagEntity::toModel) }

        /** 全部日记的标签映射：diaryId → 标签 id 列表。 */
        fun observeLinks(): Flow<Map<Long, List<Long>>> =
            tagDao.observeLinks().map { links ->
                links.groupBy({ it.diaryId }, { it.tagId })
            }

        suspend fun getAll(): List<Tag> = tagDao.getAll().map(TagEntity::toModel)

        suspend fun tagsOf(diaryId: Long): List<Long> = tagDao.tagIdsOf(diaryId)

        /**
         * 覆盖式更新单篇日记的标签集合，超出上限时截断。
         */
        suspend fun setTags(
            diaryId: Long,
            tagIds: List<Long>,
        ) {
            val limited = tagIds.distinct().take(DiaryLimits.MAX_TAGS_PER_DIARY)
            database.withTransaction {
                tagDao.clearLinks(diaryId)
                if (limited.isNotEmpty()) {
                    tagDao.insertLinks(limited.map { DiaryTagEntity(diaryId, it) })
                }
            }
        }

        /**
         * 新建标签；重名时直接返回已有标签，不做重复插入。
         */
        suspend fun createTag(
            name: String,
            now: Long = System.currentTimeMillis(),
        ): TagResult {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return TagResult.NameEmpty
            if (trimmed.length > DiaryLimits.MAX_TAG_NAME_LENGTH) return TagResult.NameTooLong
            tagDao.getByName(trimmed)?.let { return TagResult.Success(it.toModel()) }
            if (tagDao.count() >= DiaryLimits.MAX_TAGS) return TagResult.LimitReached
            val id = tagDao.insert(TagEntity(name = trimmed, createdAt = now))
            val entity = tagDao.getById(id) ?: return TagResult.Failed
            return TagResult.Success(entity.toModel())
        }

        /** 删除标签，关联记录由外键级联清理，日记本身不受影响。 */
        suspend fun deleteTag(id: Long) {
            tagDao.deleteById(id)
        }

        /** 标签使用量：tagId → 关联日记数（含回收站中的日记）。 */
        fun observeUsage(): Flow<Map<Long, Int>> =
            tagDao.observeUsage().map { rows -> rows.associate { it.tagId to it.count } }

        /**
         * 重命名标签：校验空值、长度与重名，重名时返回 [TagResult.NameDuplicate]。
         */
        suspend fun renameTag(
            id: Long,
            name: String,
        ): TagResult {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return TagResult.NameEmpty
            if (trimmed.length > DiaryLimits.MAX_TAG_NAME_LENGTH) return TagResult.NameTooLong
            val existing = tagDao.getByName(trimmed)
            if (existing != null && existing.id != id) return TagResult.NameDuplicate
            val current = tagDao.getById(id) ?: return TagResult.Failed
            if (current.name == trimmed) return TagResult.Success(current.toModel())
            tagDao.updateName(id, trimmed)
            val updated = tagDao.getById(id) ?: return TagResult.Failed
            return TagResult.Success(updated.toModel())
        }

        suspend fun usageOf(tagId: Long): Int = tagDao.tagUsage(tagId)

        /**
         * 按标签取日记，供搜索页标签筛选。
         */
        suspend fun diariesWithTag(
            tagId: Long,
            limit: Int = 100,
        ): List<Diary> = tagDao.diariesWithTag(tagId, limit).map(DiaryEntity::toModel)

        sealed interface TagResult {
            data class Success(val tag: Tag) : TagResult

            data object NameEmpty : TagResult

            data object NameTooLong : TagResult

            data object NameDuplicate : TagResult

            data object LimitReached : TagResult

            data object Failed : TagResult
        }
    }

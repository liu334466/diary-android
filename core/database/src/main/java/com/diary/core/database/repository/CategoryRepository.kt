package com.diary.core.database.repository

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.CategoryDao
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.entity.CategoryEntity
import com.diary.core.database.entity.toModel
import com.diary.core.model.Category
import com.diary.core.model.DiaryLimits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分类夹增删改查。删除分类时事务内把日记迁移到“未分类”，不删除任何日记。
 */
@Singleton
class CategoryRepository
    @Inject
    constructor(
        private val categoryDao: CategoryDao,
        private val diaryDao: DiaryDao,
        private val database: DiaryDatabase,
    ) {
        fun observeAll(): Flow<List<Category>> =
            categoryDao.observeAll().map {
                    list ->
                list.map(CategoryEntity::toModel)
            }

        suspend fun getAll(): List<Category> = categoryDao.getAll().map(CategoryEntity::toModel)

        suspend fun getById(id: Long): Category? = categoryDao.getById(id)?.toModel()

        suspend fun countCustom(): Int = categoryDao.countCustom()

        suspend fun hasDuplicateName(
            name: String,
            excludeId: Long = 0L,
        ): Boolean = categoryDao.countCustomByName(name.trim(), excludeId) > 0

        suspend fun create(
            name: String,
            now: Long = System.currentTimeMillis(),
        ): CategoryResult {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return CategoryResult.NameEmpty
            if (trimmed.length > MAX_NAME_LENGTH) return CategoryResult.NameTooLong
            if (categoryDao.countCustom() >= DiaryLimits.MAX_CATEGORIES) {
                return CategoryResult.LimitReached
            }
            val sortOrder = categoryDao.maxCustomSortOrder() + 1
            val id =
                categoryDao.insert(
                    CategoryEntity(
                        name = trimmed,
                        sortOrder = sortOrder,
                        isSystem = false,
                        createdAt = now,
                    ),
                )
            return CategoryResult.Success(id)
        }

        suspend fun rename(
            id: Long,
            name: String,
        ): CategoryResult {
            val category = categoryDao.getById(id) ?: return CategoryResult.NotFound
            if (category.isSystem) return CategoryResult.SystemCategory
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return CategoryResult.NameEmpty
            if (trimmed.length > MAX_NAME_LENGTH) return CategoryResult.NameTooLong
            categoryDao.rename(id, trimmed)
            return CategoryResult.Success(id)
        }

        /**
         * 按传入顺序重排自定义分类，系统分类不参与排序。
         */
        suspend fun reorder(orderedIds: List<Long>) {
            database.withTransaction {
                orderedIds.forEachIndexed { index, id ->
                    categoryDao.updateSortOrder(id, index + 1)
                }
            }
        }

        /**
         * 删除分类：事务内将全部日记（含回收站）迁移到“未分类”，再删除分类本身。
         * 系统分类不可删除。
         */
        suspend fun delete(id: Long): Boolean {
            val category = categoryDao.getById(id) ?: return false
            if (category.isSystem) return false
            return database.withTransaction {
                diaryDao.migrateCategory(id, DiaryLimits.UNCATEGORIZED_ID)
                categoryDao.deleteById(id) > 0
            }
        }

        sealed interface CategoryResult {
            data class Success(val id: Long) : CategoryResult

            data object NameEmpty : CategoryResult

            data object NameTooLong : CategoryResult

            data object LimitReached : CategoryResult

            data object NotFound : CategoryResult

            data object SystemCategory : CategoryResult
        }

        private companion object {
            const val MAX_NAME_LENGTH = 12
        }
    }

package com.diary.core.database.repository

import androidx.room.withTransaction
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.FontAssetDao
import com.diary.core.database.entity.FontAssetEntity
import com.diary.core.database.entity.toModel
import com.diary.core.model.FontAsset
import com.diary.core.model.FontAsset.Companion.SOURCE_IMPORTED
import com.diary.core.model.FontAsset.Companion.STATUS_INVALID
import com.diary.core.model.FontAsset.Companion.STATUS_OK
import com.diary.core.richtext.RichTextCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体元信息数据入口。删除字体时事务内清理日记与正文中的引用。
 */
@Singleton
class FontRepository
    @Inject
    constructor(
        private val fontAssetDao: FontAssetDao,
        private val diaryDao: DiaryDao,
        private val database: DiaryDatabase,
    ) {
        fun observeAll(): Flow<List<FontAsset>> =
            fontAssetDao.observeAll().map { list -> list.map(FontAssetEntity::toModel) }

        suspend fun getAll(): List<FontAsset> = fontAssetDao.getAll().map(FontAssetEntity::toModel)

        suspend fun getById(id: Long): FontAsset? = fontAssetDao.getById(id)?.toModel()

        suspend fun countImported(): Int = fontAssetDao.countImported()

        /**
         * 登记已成功拷贝并校验的导入字体。
         */
        suspend fun registerImported(
            displayName: String,
            fileName: String,
            fileSize: Long,
            now: Long = System.currentTimeMillis(),
        ): Long =
            fontAssetDao.insert(
                FontAssetEntity(
                    displayName = displayName,
                    fileName = fileName,
                    fileSize = fileSize,
                    source = SOURCE_IMPORTED,
                    status = STATUS_OK,
                    importedAt = now,
                ),
            )

        suspend fun renameDisplayName(
            id: Long,
            displayName: String,
        ) {
            val trimmed = displayName.trim()
            if (trimmed.isEmpty()) return
            fontAssetDao.rename(id, trimmed.take(MAX_DISPLAY_NAME_LENGTH))
        }

        suspend fun markStatus(
            id: Long,
            status: Int,
        ) {
            fontAssetDao.updateStatus(id, status)
        }

        suspend fun markInvalid(id: Long) = markStatus(id, STATUS_INVALID)

        /**
         * 删除用户导入字体：
         * 1. 将引用该字体的日记 font_id 置空；
         * 2. 从正文 JSON 中移除对应字体片段；
         * 3. 删除字体记录。内置字体不允许删除。
         *
         * @return 被删除的字体元信息，文件本体由字体模块清理
         */
        suspend fun delete(fontId: Long): FontAsset? {
            val font = fontAssetDao.getById(fontId)?.toModel() ?: return null
            if (font.source != SOURCE_IMPORTED) return null
            database.withTransaction {
                diaryDao.clearFontReference(fontId)
                val pattern = "%\"fontId\":$fontId%"
                diaryDao.findReferencingFont(fontId, pattern).forEach { diary ->
                    val cleaned = RichTextCodec.removeFontSpans(diary.contentJson, fontId)
                    if (cleaned != diary.contentJson) {
                        diaryDao.updateContentJson(diary.id, cleaned)
                    }
                }
                fontAssetDao.deleteById(fontId)
            }
            return font
        }

        private companion object {
            const val MAX_DISPLAY_NAME_LENGTH = 20
        }
    }

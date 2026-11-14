package com.diary.core.font

import com.diary.core.database.repository.FontRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体生命周期入口：删除时同时清理数据库引用与私有目录文件。
 */
@Singleton
class FontManager
    @Inject
    constructor(
        private val fontRepository: FontRepository,
        private val fileStore: FontFileStore,
        private val fontCache: FontCache,
    ) {
        /**
         * 删除用户导入字体。引用该字体的日记与正文片段会在事务内回退默认字体。
         *
         * @return 是否删除成功；内置字体或字体不存在返回 false
         */
        suspend fun deleteFont(fontId: Long): Boolean {
            val deleted = fontRepository.delete(fontId) ?: return false
            fileStore.delete(deleted.fileName)
            fontCache.invalidate(fontId)
            return true
        }
    }

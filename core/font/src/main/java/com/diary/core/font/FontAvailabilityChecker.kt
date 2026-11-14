package com.diary.core.font

import com.diary.core.database.repository.FontRepository
import com.diary.core.model.FontAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动时校验导入字体文件是否存在且可解析，失效字体标记为无效并触发引用回退。
 */
@Singleton
class FontAvailabilityChecker
    @Inject
    constructor(
        private val fileStore: FontFileStore,
        private val fontRepository: FontRepository,
        private val fontCache: FontCache,
    ) {
        /**
         * @return 本次新标记为无效的字体列表
         */
        suspend fun verifyAll(): List<FontAsset> =
            withContext(Dispatchers.IO) {
                fontRepository.getAll()
                    .filter { it.source == FontAsset.SOURCE_IMPORTED }
                    .mapNotNull { font ->
                        val file = fileStore.fileOf(font.fileName)
                        val valid = file.isFile && FontFileInspector.isSupportedFontFile(file)
                        if (valid) {
                            null
                        } else {
                            fontRepository.markInvalid(font.id)
                            fontCache.invalidate(font.id)
                            font.copy(status = FontAsset.STATUS_INVALID)
                        }
                    }
            }
    }

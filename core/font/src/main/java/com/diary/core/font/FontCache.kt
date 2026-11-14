package com.diary.core.font

import android.graphics.Typeface
import android.util.LruCache
import com.diary.core.database.repository.FontRepository
import com.diary.core.model.FontAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体加载结果。[usedFallback] 为 true 时调用方应回退系统字体并提示一次。
 */
data class FontLoadResult(
    val typeface: Typeface,
    val usedFallback: Boolean,
)

/**
 * Typeface 内存缓存。文件缺失或解析失败时标记字体无效并回退系统字体，不抛出异常。
 */
@Singleton
class FontCache
    @Inject
    constructor(
        private val fileStore: FontFileStore,
        private val fontRepository: FontRepository,
    ) {
        private val cache = LruCache<Long, Typeface>(MAX_CACHED)

        fun cached(fontId: Long): Typeface? = cache.get(fontId)

        suspend fun load(font: FontAsset): FontLoadResult =
            withContext(Dispatchers.IO) {
                cache.get(font.id)?.let { return@withContext FontLoadResult(it, false) }

                val file = fileStore.fileOf(font.fileName)
                if (!file.isFile || !FontFileInspector.isSupportedFontFile(file)) {
                    fontRepository.markInvalid(font.id)
                    return@withContext FontLoadResult(Typeface.DEFAULT, true)
                }

                val typeface =
                    try {
                        Typeface.createFromFile(file)
                    } catch (_: Exception) {
                        null
                    }

                if (typeface == null) {
                    fontRepository.markInvalid(font.id)
                    FontLoadResult(Typeface.DEFAULT, true)
                } else {
                    cache.put(font.id, typeface)
                    FontLoadResult(typeface, false)
                }
            }

        fun invalidate(fontId: Long) {
            cache.remove(fontId)
        }

        fun clear() {
            cache.evictAll()
        }

        private companion object {
            const val MAX_CACHED = 8
        }
    }

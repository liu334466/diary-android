package com.diary.feature.reader

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.common.DiaryImageStore
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.repository.TagRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.font.FontCache
import com.diary.core.model.Category
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontSizeLevel
import com.diary.core.richtext.RichTextCodec
import com.diary.core.richtext.RichTextDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReaderUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val diary: Diary? = null,
    val document: RichTextDocument = RichTextDocument(),
    val categories: List<Category> = emptyList(),
    val fonts: Map<Long, Typeface> = emptyMap(),
    val fontNames: Map<Long, String> = emptyMap(),
    val baseFontId: Long? = null,
    val baseSizeLevel: Int = DiaryLimits.DEFAULT_FONT_SIZE_LEVEL,
    val defaultFontId: Long? = null,
    val defaultSizeLevel: Int = DiaryLimits.DEFAULT_FONT_SIZE_LEVEL,
    val imageWidthPx: Int = 1024,
    val tags: List<String> = emptyList(),
) {
    val baseTypeface: Typeface
        get() = baseFontId?.let { fonts[it] } ?: Typeface.DEFAULT

    val baseSizeSp: Float
        get() = FontSizeLevel.sizeSpOf(baseSizeLevel)

    val categoryName: String
        get() =
            categories.firstOrNull { it.id == diary?.categoryId }?.name
                ?: DiaryLimits.UNCATEGORIZED_NAME

    val fontDisplayName: String
        get() = baseFontId?.let { fontNames[it] } ?: "系统默认"

    val sizeDisplayName: String
        get() = FontSizeLevel.of(baseSizeLevel)?.label ?: "四号"
}

@HiltViewModel
class ReaderViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val diaryRepository: DiaryRepository,
        private val categoryRepository: CategoryRepository,
        private val fontRepository: FontRepository,
        private val tagRepository: TagRepository,
        private val settingsRepository: SettingsRepository,
        private val fontCache: FontCache,
        private val imageStore: DiaryImageStore,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val diaryId: Long = savedStateHandle.get<Long>("diaryId") ?: 0L

        private val _state = MutableStateFlow(ReaderUiState())
        val state: StateFlow<ReaderUiState> = _state.asStateFlow()

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                val diary = diaryRepository.getById(diaryId)
                if (diary == null) {
                    _state.value = ReaderUiState(loading = false, missing = true)
                    return@launch
                }
                val categories = categoryRepository.getAll()
                val settings = settingsRepository.settings.first()
                val fontAssets = fontRepository.getAll()
                val typefaces = LinkedHashMap<Long, Typeface>()
                val names = LinkedHashMap<Long, String>()
                fontAssets.forEach { font ->
                    names[font.id] = font.displayName
                    val loaded = runCatching { fontCache.load(font) }.getOrNull()
                    if (loaded != null && !loaded.usedFallback) {
                        typefaces[font.id] = loaded.typeface
                    }
                }
                val document = RichTextCodec.decode(diary.contentJson)
                val width =
                    (
                        context.resources.displayMetrics.widthPixels -
                            (48 * context.resources.displayMetrics.density).toInt()
                    ).coerceAtLeast(320)
                val tagNames = tagRepository.getAll().associate { it.id to it.name }
                _state.value =
                    ReaderUiState(
                        loading = false,
                        diary = diary,
                        document = document,
                        categories = categories,
                        fonts = typefaces,
                        fontNames = names,
                        baseFontId = diary.fontId ?: settings.defaultFontId,
                        baseSizeLevel = diary.fontSizeLevel ?: settings.defaultFontSizeLevel,
                        defaultFontId = settings.defaultFontId,
                        defaultSizeLevel = settings.defaultFontSizeLevel,
                        imageWidthPx = width,
                        tags = tagRepository.tagsOf(diaryId).mapNotNull { tagNames[it] },
                    )
            }
        }

        fun applyWholeFont(fontId: Long?) {
            val diary = _state.value.diary ?: return
            viewModelScope.launch {
                diaryRepository.updateFont(diaryId, fontId, diary.fontSizeLevel)
                _state.update {
                    it.copy(diary = diary.copy(fontId = fontId), baseFontId = fontId)
                }
            }
        }

        fun applyWholeSize(level: Int) {
            val diary = _state.value.diary ?: return
            viewModelScope.launch {
                diaryRepository.updateFont(diaryId, diary.fontId, level)
                _state.update {
                    it.copy(diary = diary.copy(fontSizeLevel = level), baseSizeLevel = level)
                }
            }
        }

        fun applyGlobalFont(fontId: Long?) {
            viewModelScope.launch {
                settingsRepository.setDefaultFontId(fontId)
                _state.update { current ->
                    current.copy(
                        defaultFontId = fontId,
                        baseFontId = if (current.diary?.fontId == null) fontId else current.baseFontId,
                    )
                }
            }
        }

        fun applyGlobalSize(level: Int) {
            viewModelScope.launch {
                settingsRepository.setDefaultFontSizeLevel(level)
                _state.update { current ->
                    current.copy(
                        defaultSizeLevel = level,
                        baseSizeLevel = if (current.diary?.fontSizeLevel == null) level else current.baseSizeLevel,
                    )
                }
            }
        }

        /**
         * 清除本篇字体字号覆盖，恢复跟随全局设置（L1）。
         */
        fun resetToGlobal() {
            viewModelScope.launch {
                diaryRepository.updateFont(diaryId, null, null)
                val current = _state.value
                _state.update {
                    it.copy(
                        diary = it.diary?.copy(fontId = null, fontSizeLevel = null),
                        baseFontId = current.defaultFontId,
                        baseSizeLevel = current.defaultSizeLevel,
                    )
                }
            }
        }

        fun imageDrawable(path: String): Drawable? {
            val bitmap = imageStore.load(path, _state.value.imageWidthPx) ?: return null
            return BitmapDrawable(context.resources, bitmap)
        }

        fun delete(onDeleted: () -> Unit) {
            viewModelScope.launch {
                diaryRepository.softDelete(diaryId)
                onDeleted()
            }
        }
    }

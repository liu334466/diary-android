package com.diary.feature.editor

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
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
import com.diary.core.model.Tag
import com.diary.core.richtext.RichTextCodec
import com.diary.core.richtext.RichTextDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SaveState {
    data object Idle : SaveState

    data object Saving : SaveState

    data class Saved(val at: Long) : SaveState

    data object Failed : SaveState
}

data class EditorUiState(
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
    val saveState: SaveState = SaveState.Idle,
    val isNewDiary: Boolean = false,
    val imageWidthPx: Int = 1024,
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: List<Long> = emptyList(),
) {
    val baseTypeface: Typeface
        get() = baseFontId?.let { fonts[it] } ?: Typeface.DEFAULT

    val baseSizeSp: Float
        get() = FontSizeLevel.sizeSpOf(baseSizeLevel)

    val categoryName: String
        get() =
            categories.firstOrNull { it.id == diary?.categoryId }?.name
                ?: DiaryLimits.UNCATEGORIZED_NAME

    /** 已选标签，按标签库顺序保持稳定展示。 */
    val selectedTags: List<Tag>
        get() = tags.filter { it.id in selectedTagIds }
}

@HiltViewModel
class EditorViewModel
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

        private val _state = MutableStateFlow(EditorUiState())
        val state: StateFlow<EditorUiState> = _state.asStateFlow()

        private val saveQueue = Channel<SavePayload>(Channel.UNLIMITED)
        private var lastSavedKey: Pair<String?, String>? = null

        init {
            viewModelScope.launch {
                for (payload in saveQueue) {
                    val key = payload.title to payload.json
                    if (key == lastSavedKey) {
                        payload.onComplete()
                        continue
                    }
                    _state.update { it.copy(saveState = SaveState.Saving) }
                    val result =
                        runCatching {
                            diaryRepository.saveContent(diaryId, payload.title, payload.json)
                        }
                    _state.update {
                        it.copy(
                            saveState =
                                if (result.isSuccess) {
                                    SaveState.Saved(System.currentTimeMillis())
                                } else {
                                    SaveState.Failed
                                },
                        )
                    }
                    if (result.isSuccess) lastSavedKey = key
                    payload.onComplete()
                }
            }
            load()
        }

        private fun load() {
            viewModelScope.launch {
                val diary = diaryRepository.getById(diaryId)
                if (diary == null) {
                    _state.value = EditorUiState(loading = false, missing = true)
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
                _state.value =
                    EditorUiState(
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
                        saveState = SaveState.Saved(diary.updatedAt),
                        isNewDiary = diary.createdAt == diary.updatedAt && diary.charCount == 0,
                        imageWidthPx = width,
                        tags = tagRepository.getAll(),
                        selectedTagIds = tagRepository.tagsOf(diaryId),
                    )
            }
        }

        fun requestSave(
            title: String?,
            document: RichTextDocument,
            onComplete: () -> Unit = {},
        ) {
            val current = _state.value
            if (current.loading || current.missing || current.diary == null) {
                onComplete()
                return
            }
            if (current.isNewDiary && title.isNullOrBlank() && document.blocks.all { it.text.isBlank() }) {
                onComplete()
                return
            }
            saveQueue.trySend(SavePayload(title, RichTextCodec.encode(document), onComplete))
        }

        fun updateCategory(categoryId: Long) {
            val diary = _state.value.diary ?: return
            viewModelScope.launch {
                diaryRepository.moveToCategory(diaryId, categoryId)
                _state.update {
                    it.copy(diary = diary.copy(categoryId = categoryId))
                }
            }
        }

        /**
         * 覆盖式更新本篇标签，写入后立即刷新保存状态。
         */
        fun setTags(tagIds: List<Long>) {
            viewModelScope.launch {
                tagRepository.setTags(diaryId, tagIds)
                _state.update {
                    it.copy(
                        selectedTagIds = tagIds.distinct().take(DiaryLimits.MAX_TAGS_PER_DIARY),
                        saveState = SaveState.Saved(System.currentTimeMillis()),
                    )
                }
            }
        }

        fun applyWholeFont(fontId: Long?) {
            val diary = _state.value.diary ?: return
            viewModelScope.launch {
                diaryRepository.updateFont(diaryId, fontId, diary.fontSizeLevel)
                _state.update {
                    it.copy(
                        diary = diary.copy(fontId = fontId),
                        baseFontId = fontId,
                        saveState = SaveState.Saved(System.currentTimeMillis()),
                    )
                }
            }
        }

        fun applyWholeSize(level: Int) {
            val diary = _state.value.diary ?: return
            viewModelScope.launch {
                diaryRepository.updateFont(diaryId, diary.fontId, level)
                _state.update {
                    it.copy(
                        diary = diary.copy(fontSizeLevel = level),
                        baseSizeLevel = level,
                        saveState = SaveState.Saved(System.currentTimeMillis()),
                    )
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

        fun typefaceOf(fontId: Long?): Typeface? = fontId?.let { _state.value.fonts[it] }

        fun imageWidthPx(): Int = _state.value.imageWidthPx

        fun imageDrawable(path: String): Drawable? {
            val bitmap = imageStore.load(path, imageWidthPx()) ?: return null
            return BitmapDrawable(context.resources, bitmap)
        }

        suspend fun saveImage(uri: Uri): String? =
            withContext(Dispatchers.IO) {
                imageStore.save(uri)
            }

        fun deleteDiary(onDeleted: () -> Unit) {
            viewModelScope.launch {
                diaryRepository.softDelete(diaryId)
                onDeleted()
            }
        }

        /**
         * 新建后从未写入内容的空白日记，退出时直接物理删除，避免留下“未命名日记”。
         */
        fun discardIfBlank(
            blank: Boolean,
            onComplete: () -> Unit,
        ) {
            val current = _state.value
            if (!blank || !current.isNewDiary) {
                onComplete()
                return
            }
            viewModelScope.launch {
                diaryRepository.deletePermanently(diaryId)
                onComplete()
            }
        }

        private data class SavePayload(
            val title: String?,
            val json: String,
            val onComplete: () -> Unit,
        )
    }

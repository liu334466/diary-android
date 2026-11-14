package com.diary.feature.fontsetting

import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.common.DiaryFormats
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.font.FontCache
import com.diary.core.font.FontImportResult
import com.diary.core.font.FontImporter
import com.diary.core.font.FontManager
import com.diary.core.model.FontAsset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 字体管理行。font 为 null 时表示系统默认字体。
 */
data class FontRowItem(
    val font: FontAsset?,
    val name: String,
    val meta: String,
    val isDefault: Boolean,
    val refCount: Int,
    val typeface: Typeface,
    val invalid: Boolean = false,
)

data class FontUiState(
    val items: List<FontRowItem> = emptyList(),
    val importedCount: Int = 0,
    val loading: Boolean = true,
)

@HiltViewModel
class FontManagementViewModel
    @Inject
    constructor(
        private val fontRepository: FontRepository,
        private val settingsRepository: SettingsRepository,
        private val diaryRepository: DiaryRepository,
        private val fontImporter: FontImporter,
        private val fontManager: FontManager,
        private val fontCache: FontCache,
    ) : ViewModel() {
        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow = messages.receiveAsFlow()

        private val typefaces = MutableStateFlow<Map<Long, Typeface>>(emptyMap())

        init {
            viewModelScope.launch {
                fontRepository.observeAll().collect { fonts ->
                    val loaded = LinkedHashMap<Long, Typeface>()
                    fonts.forEach { font ->
                        if (font.status == FontAsset.STATUS_OK) {
                            val result = runCatching { fontCache.load(font) }.getOrNull()
                            if (result != null && !result.usedFallback) {
                                loaded[font.id] = result.typeface
                            }
                        }
                    }
                    typefaces.value = loaded
                }
            }
        }

        val state: StateFlow<FontUiState> =
            combine(
                fontRepository.observeAll(),
                settingsRepository.settings,
                diaryRepository.observeFontRefCounts(),
                typefaces,
            ) { fonts, settings, refCounts, loaded ->
                val items = ArrayList<FontRowItem>()
                items +=
                    FontRowItem(
                        font = null,
                        name = "系统默认",
                        meta = "内置字体 · 跟随系统",
                        isDefault = settings.defaultFontId == null,
                        refCount = 0,
                        typeface = Typeface.DEFAULT,
                    )
                fonts.sortedBy { it.importedAt }.forEach { font ->
                    val invalid = font.status != FontAsset.STATUS_OK
                    items +=
                        FontRowItem(
                            font = font,
                            name = font.displayName,
                            meta =
                                buildString {
                                    append(if (font.source == FontAsset.SOURCE_IMPORTED) "导入" else "内置")
                                    append(" · ")
                                    append(DiaryFormats.fileSize(font.fileSize))
                                    val refs = refCounts[font.id] ?: 0
                                    if (refs > 0) append(" · $refs 篇在用")
                                    if (invalid) append(" · 文件缺失")
                                },
                            isDefault = settings.defaultFontId == font.id,
                            refCount = refCounts[font.id] ?: 0,
                            typeface = loaded[font.id] ?: Typeface.DEFAULT,
                            invalid = invalid,
                        )
                }
                FontUiState(
                    items = items,
                    importedCount = fonts.count { it.source == FontAsset.SOURCE_IMPORTED },
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FontUiState())

        fun importFont(uri: Uri) {
            viewModelScope.launch {
                when (val result = fontImporter.import(uri)) {
                    is FontImportResult.Success ->
                        messages.send("已导入「${result.font.displayName}」")
                    is FontImportResult.Failure -> messages.send(result.message)
                }
            }
        }

        fun rename(
            font: FontAsset,
            name: String,
        ) {
            viewModelScope.launch {
                fontRepository.renameDisplayName(font.id, name)
                messages.send("已重命名为「$name」")
            }
        }

        fun setDefault(fontId: Long?) {
            viewModelScope.launch {
                settingsRepository.setDefaultFontId(fontId)
                messages.send(if (fontId == null) "已设为系统默认字体" else "已设为默认字体")
            }
        }

        fun delete(font: FontAsset) {
            viewModelScope.launch {
                val wasDefault = settingsRepository.settings.first().defaultFontId == font.id
                val deleted = fontManager.deleteFont(font.id)
                if (!deleted) {
                    messages.send("删除失败，请重试")
                    return@launch
                }
                if (wasDefault) settingsRepository.setDefaultFontId(null)
                messages.send("已删除「${font.displayName}」，引用它的日记已回退系统字体")
            }
        }
    }

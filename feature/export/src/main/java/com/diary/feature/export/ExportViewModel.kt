package com.diary.feature.export

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.common.DiaryFormats
import com.diary.core.common.DiaryImageStore
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.font.FontCache
import com.diary.core.model.DiaryLimits
import com.diary.core.richtext.RichTextCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 导出弹层状态机：选择格式 → 生成中（可取消）→ 完成（可分享）/ 失败（可重试）。
 */
enum class ExportStage {
    CHOOSE,
    PROGRESS,
    DONE,
    FAIL,
}

data class ExportUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val title: String = "",
    val meta: String = "",
    val format: ExportFormat = ExportFormat.PDF,
    val stage: ExportStage = ExportStage.CHOOSE,
    val progress: Int = 0,
    val resultName: String = "",
    val resultDetail: String = "",
    val segmented: Boolean = false,
    val errorMessage: String = "",
    val longContent: Boolean = false,
)

@HiltViewModel
class ExportViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val diaryRepository: DiaryRepository,
        private val categoryRepository: CategoryRepository,
        private val fontRepository: FontRepository,
        private val settingsRepository: SettingsRepository,
        private val fontCache: FontCache,
        private val imageStore: DiaryImageStore,
        private val fileStore: ExportFileStore,
        private val pngExporter: PngExporter,
        private val pdfExporter: PdfExporter,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val diaryId: Long = savedStateHandle.get<Long>("diaryId") ?: 0L

        private val _state = MutableStateFlow(ExportUiState())
        val state: StateFlow<ExportUiState> = _state.asStateFlow()

        private var content: ExportContent? = null
        private var resultFiles: List<File> = emptyList()
        private var exportJob: Job? = null
        private var exportToken = 0

        init {
            load()
        }

        fun load() {
            viewModelScope.launch {
                val diary = diaryRepository.getById(diaryId)
                if (diary == null) {
                    _state.update { it.copy(loading = false, missing = true) }
                    return@launch
                }
                val categories = categoryRepository.getAll()
                val settings = settingsRepository.settings.first()
                val fontAssets = fontRepository.getAll()
                val typefaces = LinkedHashMap<Long, Typeface>()
                fontAssets.forEach { font ->
                    val loaded = runCatching { fontCache.load(font) }.getOrNull()
                    if (loaded != null && !loaded.usedFallback) {
                        typefaces[font.id] = loaded.typeface
                    }
                }
                val document = RichTextCodec.decode(diary.contentJson)
                val baseFontId = diary.fontId ?: settings.defaultFontId
                val baseSizeLevel = diary.fontSizeLevel ?: settings.defaultFontSizeLevel
                val categoryName =
                    categories.firstOrNull { it.id == diary.categoryId }?.name
                        ?: DiaryLimits.UNCATEGORIZED_NAME
                val displayTitle =
                    diary.title?.takeIf { it.isNotBlank() }
                        ?: DiaryLimits.UNTITLED_DIARY_NAME

                content =
                    ExportContent(
                        title = displayTitle,
                        meta =
                            listOf(
                                DiaryFormats.date(diary.createdAt),
                                categoryName,
                                "${DiaryFormats.charCount(diary.charCount)} 字",
                            ).joinToString(" · "),
                        document = document,
                        baseTypeface = baseFontId?.let { typefaces[it] } ?: Typeface.DEFAULT,
                        baseSizeSp = com.diary.core.model.FontSizeLevel.sizeSpOf(baseSizeLevel),
                        fonts = typefaces,
                        imageResolver = { path ->
                            imageStore.load(path, IMAGE_LOAD_WIDTH_PX)?.let { bitmap ->
                                BitmapDrawable(context.resources, bitmap)
                            }
                        },
                    )
                _state.update {
                    it.copy(
                        loading = false,
                        title = displayTitle,
                        meta = content?.meta.orEmpty(),
                        longContent = diary.charCount > LONG_CONTENT_CHARS,
                    )
                }
            }
        }

        fun selectFormat(format: ExportFormat) {
            if (_state.value.stage == ExportStage.PROGRESS) return
            _state.update { it.copy(format = format) }
        }

        fun start() {
            val exportContent = content ?: return
            val format = _state.value.format
            exportJob?.cancel()
            val token = ++exportToken
            fileStore.clear()
            _state.update {
                it.copy(
                    stage = ExportStage.PROGRESS,
                    progress = 0,
                    resultName = "",
                    resultDetail = "",
                    segmented = false,
                    errorMessage = "",
                )
            }
            exportJob =
                viewModelScope.launch {
                    try {
                        val files =
                            withContext(Dispatchers.Default) {
                                when (format) {
                                    ExportFormat.PNG ->
                                        pngExporter.export(
                                            content = exportContent,
                                            directory = fileStore.directory(),
                                            baseName = fileStore.baseName(exportContent.title),
                                            onProgress = { progress ->
                                                _state.update { it.copy(progress = progress) }
                                            },
                                            isCancelled = { !isActive },
                                        )

                                    ExportFormat.PDF ->
                                        listOf(
                                            pdfExporter.export(
                                                content = exportContent,
                                                file =
                                                    File(
                                                        fileStore.directory(),
                                                        "${fileStore.baseName(exportContent.title)}.pdf",
                                                    ),
                                                onProgress = { progress ->
                                                    _state.update { it.copy(progress = progress) }
                                                },
                                                isCancelled = { !isActive },
                                            ),
                                        )
                                }
                            }
                        if (files.isEmpty()) error("没有生成文件")
                        if (token != exportToken) return@launch
                        resultFiles = files
                        _state.update {
                            it.copy(
                                stage = ExportStage.DONE,
                                progress = 100,
                                resultName = files.singleOrNull()?.name ?: "共 ${files.size} 张长图",
                                resultDetail = "${DiaryFormats.fileSize(files.sumOf(File::length))} · 已保存到应用缓存，可直接分享",
                                segmented = files.size > 1,
                            )
                        }
                    } catch (_: CancellationException) {
                        if (token != exportToken) return@launch
                        resultFiles = emptyList()
                        _state.update { it.copy(stage = ExportStage.CHOOSE, progress = 0) }
                    } catch (throwable: Throwable) {
                        if (token != exportToken) return@launch
                        resultFiles = emptyList()
                        fileStore.clear()
                        _state.update {
                            it.copy(
                                stage = ExportStage.FAIL,
                                progress = 0,
                                errorMessage = errorMessageOf(throwable),
                            )
                        }
                    }
                }
        }

        fun cancel() {
            exportToken++
            exportJob?.cancel()
            exportJob = null
            resultFiles = emptyList()
            _state.update { it.copy(stage = ExportStage.CHOOSE, progress = 0) }
        }

        fun filesForShare(): List<File> = resultFiles

        override fun onCleared() {
            exportJob?.cancel()
            super.onCleared()
        }

        private fun errorMessageOf(throwable: Throwable): String =
            when (throwable) {
                is OutOfMemoryError -> "内容过长导致内存不足。可以调小字号后重新导出，或换用 PDF 格式。"
                else ->
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: "生成过程中出现异常，已清理临时文件，可以重试。"
            }

        private companion object {
            const val LONG_CONTENT_CHARS = 5_000
            const val IMAGE_LOAD_WIDTH_PX = 998
        }
    }

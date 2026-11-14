package com.diary.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.backup.BackupManager
import com.diary.core.common.DiaryThemeMode
import com.diary.core.common.ThemeModeStore
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.FontRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontAsset
import com.diary.core.model.FontSizeLevel
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

data class SettingsUiState(
    val defaultFontId: Long? = null,
    val defaultFontName: String = "系统默认",
    val defaultFontMissing: Boolean = false,
    val defaultSizeLevel: Int = DiaryLimits.DEFAULT_FONT_SIZE_LEVEL,
    val importedFontCount: Int = 0,
    val trashCount: Int = 0,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val themeMode: DiaryThemeMode = DiaryThemeMode.SYSTEM,
) {
    val defaultSizeName: String
        get() = FontSizeLevel.of(defaultSizeLevel)?.label ?: "四号"
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val fontRepository: FontRepository,
        private val diaryRepository: DiaryRepository,
        private val backupManager: BackupManager,
        private val themeModeStore: ThemeModeStore,
    ) : ViewModel() {
        private val busy = MutableStateFlow(false)
        private val themeMode = MutableStateFlow(themeModeStore.mode)
        private val eventChannel = Channel<String>(Channel.BUFFERED)

        /** 备份/恢复操作的一次性提示，由设置页 Toast 展示。 */
        val events = eventChannel.receiveAsFlow()

        val state: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.settings,
                fontRepository.observeAll(),
                diaryRepository.observeTrashCount(),
                busy,
                themeMode,
            ) { settings, fonts, trashCount, running, mode ->
                val defaultFont = settings.defaultFontId?.let { id -> fonts.firstOrNull { it.id == id } }
                SettingsUiState(
                    defaultFontId = settings.defaultFontId,
                    defaultFontName = defaultFont?.displayName ?: "系统默认",
                    defaultFontMissing = settings.defaultFontId != null && defaultFont == null,
                    defaultSizeLevel = settings.defaultFontSizeLevel,
                    importedFontCount = fonts.count { it.source == FontAsset.SOURCE_IMPORTED },
                    trashCount = trashCount,
                    loading = false,
                    busy = running,
                    themeMode = mode,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        fun setDefaultFont(fontId: Long?) {
            viewModelScope.launch {
                settingsRepository.setDefaultFontId(fontId)
            }
        }

        fun setDefaultSize(level: Int) {
            viewModelScope.launch {
                settingsRepository.setDefaultFontSizeLevel(level)
            }
        }

        /**
         * 保存外观偏好。主题切换需要重建 Activity 才能让全部页面重新取色。
         */
        fun setThemeMode(mode: DiaryThemeMode) {
            if (themeMode.value == mode) return
            themeModeStore.mode = mode
            themeMode.value = mode
        }

        /**
         * 导出备份到系统文件选择器返回的目标位置。
         */
        fun exportBackup(uri: Uri) {
            if (busy.value) return
            viewModelScope.launch {
                busy.value = true
                try {
                    val summary = backupManager.export(uri)
                    eventChannel.send(
                        "备份已导出：${summary.diaryCount} 篇日记、${summary.imageCount} 张插图",
                    )
                } catch (e: Exception) {
                    eventChannel.send(e.message ?: "导出备份失败")
                } finally {
                    busy.value = false
                }
            }
        }

        /**
         * 从备份文件覆盖式恢复；恢复后清理指向已不存在字体的全局默认字体。
         */
        fun restoreBackup(uri: Uri) {
            if (busy.value) return
            viewModelScope.launch {
                busy.value = true
                try {
                    val summary = backupManager.restore(uri)
                    ensureDefaultFontValid()
                    eventChannel.send(
                        "已恢复 ${summary.diaryCount} 篇日记、${summary.tagCount} 个标签",
                    )
                } catch (e: Exception) {
                    eventChannel.send(e.message ?: "恢复备份失败")
                } finally {
                    busy.value = false
                }
            }
        }

        private suspend fun ensureDefaultFontValid() {
            val settings = settingsRepository.settings.first()
            val fontId = settings.defaultFontId ?: return
            if (fontRepository.getById(fontId) == null) {
                settingsRepository.setDefaultFontId(null)
            }
        }
    }

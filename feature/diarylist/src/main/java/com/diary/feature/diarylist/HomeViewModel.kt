package com.diary.feature.diarylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.TagRepository
import com.diary.core.database.settings.SettingsRepository
import com.diary.core.model.Category
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页状态：分类标签、当前分类下的日记列表，并记住上次浏览分类。
 */
data class HomeUiState(
    val categories: List<Category> = emptyList(),
    val diaries: List<Diary> = emptyList(),
    val selectedCategoryId: Long = 0L,
    val loading: Boolean = true,
    val tagNames: Map<Long, List<String>> = emptyMap(),
) {
    val categoryNames: Map<Long, String>
        get() = categories.associate { it.id to it.name }

    val selectedCategoryName: String
        get() =
            if (selectedCategoryId == 0L) {
                "全部"
            } else {
                categoryNames[selectedCategoryId] ?: DiaryLimits.UNCATEGORIZED_NAME
            }
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val diaryRepository: DiaryRepository,
        private val categoryRepository: CategoryRepository,
        private val tagRepository: TagRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val selectedCategory = MutableStateFlow<Long?>(null)

        private val editorEvents = Channel<Long>(Channel.BUFFERED)
        val openEditor = editorEvents.receiveAsFlow()

        private val diariesFlow =
            selectedCategory.flatMapLatest { categoryId ->
                if (categoryId == null || categoryId == 0L) {
                    diaryRepository.observeActive()
                } else {
                    diaryRepository.observeActiveByCategory(categoryId)
                }
            }

        val state: StateFlow<HomeUiState> =
            combine(
                categoryRepository.observeAll(),
                selectedCategory.filterNotNull(),
                diariesFlow,
                tagRepository.observeAll(),
                tagRepository.observeLinks(),
            ) { categories, selected, diaries, tags, links ->
                val effective =
                    if (selected == 0L || categories.any { it.id == selected }) {
                        selected
                    } else {
                        0L
                    }
                val nameById = tags.associate { it.id to it.name }
                HomeUiState(
                    categories = categories,
                    diaries = diaries,
                    selectedCategoryId = effective,
                    loading = false,
                    tagNames = links.mapValues { (_, ids) -> ids.mapNotNull { nameById[it] } },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

        init {
            viewModelScope.launch {
                val settings = settingsRepository.settings.first()
                selectedCategory.value = settings.lastCategoryId
            }
        }

        fun selectCategory(categoryId: Long) {
            if (selectedCategory.value == categoryId) return
            selectedCategory.value = categoryId
            viewModelScope.launch {
                settingsRepository.setLastCategoryId(categoryId)
            }
        }

        /**
         * 新建空白日记并进入编辑页；当前为“全部”时落入未分类。
         */
        fun createDiary() {
            viewModelScope.launch {
                val target =
                    selectedCategory.value
                        ?.takeIf { it != 0L }
                        ?: DiaryLimits.UNCATEGORIZED_ID
                val id = diaryRepository.create(categoryId = target)
                editorEvents.send(id)
            }
        }
    }

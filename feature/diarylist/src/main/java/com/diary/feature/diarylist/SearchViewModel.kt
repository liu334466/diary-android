package com.diary.feature.diarylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.TagRepository
import com.diary.core.model.Category
import com.diary.core.model.Diary
import com.diary.core.model.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 搜索结果状态。[hasSearched] 为 false 表示既没有关键词也没有选中标签，用于区分空态文案。
 */
data class SearchUiState(
    val query: String = "",
    val results: List<Diary> = emptyList(),
    val categories: List<Category> = emptyList(),
    val hasSearched: Boolean = false,
    val tags: List<Tag> = emptyList(),
    val selectedTagId: Long? = null,
    val tagNames: Map<Long, List<String>> = emptyMap(),
) {
    val categoryNames: Map<Long, String>
        get() = categories.associate { it.id to it.name }

    val selectedTagName: String?
        get() = tags.firstOrNull { it.id == selectedTagId }?.name
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val diaryRepository: DiaryRepository,
        private val categoryRepository: CategoryRepository,
        private val tagRepository: TagRepository,
    ) : ViewModel() {
        private val queryFlow = MutableStateFlow("")
        private val selectedTag = MutableStateFlow<Long?>(null)

        private val categoriesFlow = categoryRepository.observeAll()

        private val keywordFlow =
            queryFlow
                .map { it.trim() }
                .debounce(DEBOUNCE_MILLIS)
                .distinctUntilChanged()

        private val resultFlow =
            combine(keywordFlow, selectedTag) { keyword, tagId ->
                keyword to tagId
            }.flatMapLatest { (keyword, tagId) ->
                if (keyword.isEmpty() && tagId == null) {
                    flowOf(SearchHit(emptyList(), false))
                } else {
                    flow { emit(SearchHit(resolve(keyword, tagId), true)) }
                }
            }

        val state: StateFlow<SearchUiState> =
            combine(
                queryFlow,
                resultFlow,
                categoriesFlow,
                tagRepository.observeAll(),
                tagRepository.observeLinks(),
            ) { raw, hit, categories, tags, links ->
                val nameById = tags.associate { it.id to it.name }
                SearchUiState(
                    query = raw,
                    results = hit.results,
                    categories = categories,
                    hasSearched = hit.hasSearched,
                    tags = tags,
                    selectedTagId = selectedTag.value,
                    tagNames = links.mapValues { (_, ids) -> ids.mapNotNull { nameById[it] } },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

        /**
         * 关键词与标签组合检索：两者都为条件时取交集，仅有标签时按标签列出。
         */
        private suspend fun resolve(
            keyword: String,
            tagId: Long?,
        ): List<Diary> =
            when {
                tagId == null -> diaryRepository.search(keyword)
                keyword.isEmpty() -> tagRepository.diariesWithTag(tagId)
                else -> {
                    val tagged =
                        tagRepository.diariesWithTag(tagId, TAG_FILTER_LIMIT)
                            .mapTo(HashSet()) { it.id }
                    diaryRepository.search(keyword).filter { it.id in tagged }
                }
            }

        fun onQueryChange(text: String) {
            queryFlow.value = text
        }

        fun selectTag(tagId: Long?) {
            selectedTag.value = tagId
        }

        fun clear() {
            queryFlow.value = ""
            selectedTag.value = null
        }

        private data class SearchHit(
            val results: List<Diary>,
            val hasSearched: Boolean,
        )

        private companion object {
            const val DEBOUNCE_MILLIS = 250L

            /** 组合筛选时按标签取日记的上限，避免极端数据量拖慢查询。 */
            const val TAG_FILTER_LIMIT = 1_000
        }
    }

package com.diary.feature.diarylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.model.Diary
import com.diary.core.model.DiaryLimits
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TrashItem(
    val diary: Diary,
    val categoryName: String,
    val remainingDays: Int,
)

data class TrashUiState(
    val items: List<TrashItem> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class TrashViewModel
    @Inject
    constructor(
        private val diaryRepository: DiaryRepository,
        private val categoryRepository: CategoryRepository,
    ) : ViewModel() {
        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow = messages.receiveAsFlow()

        val state: StateFlow<TrashUiState> =
            combine(
                diaryRepository.observeTrash(),
                categoryRepository.observeAll(),
            ) { diaries, categories ->
                val names = categories.associate { it.id to it.name }
                val now = System.currentTimeMillis()
                TrashUiState(
                    items =
                        diaries.map { diary ->
                            TrashItem(
                                diary = diary,
                                categoryName = names[diary.categoryId] ?: DiaryLimits.UNCATEGORIZED_NAME,
                                remainingDays = diaryRepository.trashRemainingDays(diary.deletedAt, now),
                            )
                        },
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrashUiState())

        init {
            viewModelScope.launch {
                val removed = diaryRepository.purgeExpired()
                if (removed > 0) {
                    messages.send("已自动清理 $removed 篇超过 30 天的日记")
                }
            }
        }

        fun restore(item: TrashItem) {
            viewModelScope.launch {
                val restored = diaryRepository.restore(item.diary.id)
                messages.send(
                    if (restored) {
                        "已恢复「${item.diary.title ?: DiaryLimits.UNTITLED_DIARY_NAME}」"
                    } else {
                        "恢复失败，请重试"
                    },
                )
            }
        }

        fun purge(item: TrashItem) {
            viewModelScope.launch {
                diaryRepository.deletePermanently(item.diary.id)
                messages.send("已彻底删除，无法恢复")
            }
        }

        fun clearAll() {
            viewModelScope.launch {
                diaryRepository.clearTrash()
                messages.send("回收站已清空")
            }
        }
    }

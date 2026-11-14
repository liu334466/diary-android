package com.diary.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.TagRepository
import com.diary.core.model.DiaryLimits
import com.diary.core.model.Tag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 标签管理列表项：标签本体与关联日记数。
 */
data class TagItem(
    val tag: Tag,
    val usage: Int,
)

data class TagManagementUiState(
    val tags: List<TagItem> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class TagManagementViewModel
    @Inject
    constructor(
        private val tagRepository: TagRepository,
    ) : ViewModel() {
        private val eventChannel = Channel<String>(Channel.BUFFERED)

        /** 重命名结果提示，由页面 Toast 展示。 */
        val events = eventChannel.receiveAsFlow()

        val state: StateFlow<TagManagementUiState> =
            combine(
                tagRepository.observeAll(),
                tagRepository.observeUsage(),
            ) { tags, usage ->
                TagManagementUiState(
                    tags = tags.map { TagItem(tag = it, usage = usage[it.id] ?: 0) },
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TagManagementUiState())

        fun rename(
            tag: Tag,
            name: String,
        ) {
            viewModelScope.launch {
                when (val result = tagRepository.renameTag(tag.id, name)) {
                    is TagRepository.TagResult.Success -> eventChannel.send("标签已重命名")
                    TagRepository.TagResult.NameEmpty -> eventChannel.send("标签名不能为空")
                    TagRepository.TagResult.NameTooLong ->
                        eventChannel.send("标签名最多 ${DiaryLimits.MAX_TAG_NAME_LENGTH} 个字")
                    TagRepository.TagResult.NameDuplicate -> eventChannel.send("已有同名标签")
                    else -> eventChannel.send("重命名失败，请重试")
                }
            }
        }

        fun delete(tag: Tag) {
            viewModelScope.launch {
                runCatching { tagRepository.deleteTag(tag.id) }
                    .onSuccess { eventChannel.send("已删除标签「${tag.name}」") }
                    .onFailure { eventChannel.send("删除失败，请重试") }
            }
        }
    }

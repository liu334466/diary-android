package com.diary.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.model.Category
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

/**
 * 分类管理页状态：分类 + 夹内日记数量。
 */
data class CategoryRow(
    val category: Category,
    val count: Int,
)

data class CategoryUiState(
    val rows: List<CategoryRow> = emptyList(),
    val loading: Boolean = true,
) {
    val customRows: List<CategoryRow> get() = rows.filter { !it.category.isSystem }

    val customCount: Int get() = customRows.size

    val limitReached: Boolean get() = customCount >= DiaryLimits.MAX_CATEGORIES
}

@HiltViewModel
class CategoryViewModel
    @Inject
    constructor(
        private val categoryRepository: CategoryRepository,
        private val diaryRepository: DiaryRepository,
    ) : ViewModel() {
        private val messageChannel = Channel<String>(Channel.BUFFERED)
        val messages = messageChannel.receiveAsFlow()

        val state: StateFlow<CategoryUiState> =
            combine(
                categoryRepository.observeAll(),
                diaryRepository.observeActiveCounts(),
            ) { categories, counts ->
                CategoryUiState(
                    rows = categories.map { CategoryRow(it, counts[it.id] ?: 0) },
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryUiState())

        suspend fun hasDuplicate(
            name: String,
            excludeId: Long = 0L,
        ): Boolean = categoryRepository.hasDuplicateName(name, excludeId)

        fun create(name: String) {
            viewModelScope.launch {
                when (val result = categoryRepository.create(name)) {
                    is CategoryRepository.CategoryResult.Success ->
                        messageChannel.send("已创建「$name」")
                    CategoryRepository.CategoryResult.LimitReached ->
                        messageChannel.send("已达到 20 个分类上限，请先删除一个")
                    CategoryRepository.CategoryResult.NameEmpty ->
                        messageChannel.send("分类名称不能为空")
                    CategoryRepository.CategoryResult.NameTooLong ->
                        messageChannel.send("分类名称最多 12 个字")
                    else -> messageChannel.send("创建失败，请重试")
                }
            }
        }

        fun rename(
            id: Long,
            name: String,
        ) {
            viewModelScope.launch {
                when (categoryRepository.rename(id, name)) {
                    is CategoryRepository.CategoryResult.Success ->
                        messageChannel.send("已重命名为「$name」")
                    CategoryRepository.CategoryResult.NameEmpty ->
                        messageChannel.send("分类名称不能为空")
                    CategoryRepository.CategoryResult.NameTooLong ->
                        messageChannel.send("分类名称最多 12 个字")
                    else -> messageChannel.send("重命名失败，请重试")
                }
            }
        }

        /**
         * 上移/下移只调整自定义分类顺序，系统分类固定置顶。
         */
        fun move(
            categoryId: Long,
            offset: Int,
        ) {
            val custom = state.value.customRows.map { it.category }
            val index = custom.indexOfFirst { it.id == categoryId }
            if (index < 0) return
            val target = index + offset
            if (target !in custom.indices) {
                viewModelScope.launch {
                    messageChannel.send(if (offset < 0) "已经在最前" else "已经在最后")
                }
                return
            }
            val reordered =
                custom.toMutableList().apply {
                    val item = removeAt(index)
                    add(target, item)
                }
            viewModelScope.launch {
                categoryRepository.reorder(reordered.map { it.id })
                messageChannel.send(if (offset < 0) "已上移" else "已下移")
            }
        }

        fun delete(
            categoryId: Long,
            name: String,
            count: Int,
        ) {
            viewModelScope.launch {
                val deleted = categoryRepository.delete(categoryId)
                messageChannel.send(
                    when {
                        !deleted -> "删除失败，请重试"
                        count > 0 -> "已删除，移入未分类 $count 篇"
                        else -> "已删除「$name」"
                    },
                )
            }
        }
    }

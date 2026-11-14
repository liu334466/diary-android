package com.diary.feature.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.diary.core.designsystem.DiaryUi
import com.diary.core.model.Category
import com.diary.core.model.DiaryLimits
import com.diary.feature.category.databinding.FragmentCategoryBinding
import com.diary.feature.category.databinding.ItemCategoryBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 分类管理：新建、重命名、排序、删除迁移，20 个自定义分类上限。
 */
@AndroidEntryPoint
class CategoryFragment : Fragment() {
    private var _binding: FragmentCategoryBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: CategoryViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCategoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.newCategory.setOnClickListener { handleCreate() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messages.collect { DiaryUi.toast(requireContext(), it) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: CategoryUiState) {
        if (state.loading) return
        binding.categoryList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        state.rows.forEach { row ->
            val item = ItemCategoryBinding.inflate(inflater, binding.categoryList, false)
            item.name.text = row.category.name
            item.badge.isVisible = row.category.isSystem
            item.count.text = "${row.count} 篇日记"
            item.moreButton.isVisible = !row.category.isSystem
            item.moreButton.setOnClickListener { showActions(row) }
            item.root.setOnClickListener {
                if (row.category.isSystem) {
                    DiaryUi.toast(requireContext(), "「未分类」为系统分类，不可重命名或删除")
                } else {
                    showActions(row)
                }
            }
            binding.categoryList.addView(item.root)
        }
        binding.limitHint.text =
            if (state.limitReached) {
                "已使用 ${state.customCount} / ${DiaryLimits.MAX_CATEGORIES} 个自定义分类　已达上限，需先删除一个"
            } else {
                "已使用 ${state.customCount} / ${DiaryLimits.MAX_CATEGORIES} 个自定义分类"
            }
        binding.newCategory.alpha = if (state.limitReached) 0.4f else 1f
        binding.newCategory.isEnabled = !state.limitReached
    }

    private fun handleCreate() {
        if (viewModel.state.value.limitReached) {
            DiaryUi.toast(requireContext(), "已达到 20 个分类上限，请先删除一个")
            return
        }
        promptName(
            title = "新建分类",
            subtitle = "1–12 个字，允许重名但会再次确认；新分类排在最后",
            initial = "",
            action = "创建",
            excludeId = 0L,
        ) { name ->
            viewModel.create(name)
        }
    }

    private fun showActions(row: CategoryRow) {
        val custom = viewModel.state.value.customRows
        val index = custom.indexOfFirst { it.category.id == row.category.id }
        DiaryUi.actionSheet(
            context = requireContext(),
            title = "「${row.category.name}」",
            subtitle = "${row.count} 篇日记",
            items =
                listOf(
                    DiaryUi.ActionItem("重命名"),
                    DiaryUi.ActionItem("上移", enabled = index > 0),
                    DiaryUi.ActionItem("下移", enabled = index >= 0 && index < custom.lastIndex),
                    DiaryUi.ActionItem("删除分类", danger = true),
                ),
        ) { selected ->
            when (selected) {
                0 ->
                    promptName(
                        title = "重命名分类",
                        subtitle = "1–12 个字，允许重名但会再次确认",
                        initial = row.category.name,
                        action = "保存",
                        excludeId = row.category.id,
                    ) { name ->
                        viewModel.rename(row.category.id, name)
                    }
                1 -> viewModel.move(row.category.id, -1)
                2 -> viewModel.move(row.category.id, 1)
                3 -> confirmDelete(row.category, row.count)
            }
        }
    }

    private fun promptName(
        title: String,
        subtitle: String,
        initial: String,
        action: String,
        excludeId: Long,
        commit: (String) -> Unit,
    ) {
        DiaryUi.inputDialog(
            context = requireContext(),
            title = title,
            subtitle = subtitle,
            initialValue = initial,
            placeholder = "例如：读书",
            maxLength = 12,
            confirmText = action,
        ) { name ->
            viewLifecycleOwner.lifecycleScope.launch {
                val duplicate = viewModel.hasDuplicate(name, excludeId)
                if (duplicate) {
                    DiaryUi.confirm(
                        context = requireContext(),
                        title = "已存在同名分类",
                        message = "仍然使用「$name」作为分类名称吗？两个分类会同时保留。",
                        confirmText = "仍然保存",
                        danger = false,
                        onConfirm = { commit(name) },
                    )
                } else {
                    commit(name)
                }
            }
        }
    }

    private fun confirmDelete(
        category: Category,
        count: Int,
    ) {
        DiaryUi.confirm(
            context = requireContext(),
            title = "删除「${category.name}」？",
            message =
                if (count > 0) {
                    "夹内 $count 篇日记会移入「未分类」，日记的内容、字体与格式都不会改变。"
                } else {
                    "该分类下没有日记，删除后不可恢复。"
                },
            confirmText = "删除分类",
            onConfirm = { viewModel.delete(category.id, category.name, count) },
        )
    }
}

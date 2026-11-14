package com.diary.feature.diarylist

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
import androidx.navigation.fragment.findNavController
import com.diary.core.common.DiaryFormats
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.model.DiaryLimits
import com.diary.feature.diarylist.databinding.FragmentTrashBinding
import com.diary.feature.diarylist.databinding.ItemTrashBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 回收站：剩余天数、单条恢复、彻底删除与清空。
 */
@AndroidEntryPoint
class TrashFragment : Fragment() {
    private var _binding: FragmentTrashBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: TrashViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.clearButton.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.messageFlow.collect { DiaryUi.toast(requireContext(), it) }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: TrashUiState) {
        if (state.loading) return
        binding.trashList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        state.items.forEach { item ->
            val row = ItemTrashBinding.inflate(inflater, binding.trashList, false)
            val diary = item.diary
            val hasTitle = !diary.title.isNullOrBlank()
            row.title.text = if (hasTitle) diary.title else DiaryLimits.UNTITLED_DIARY_NAME
            row.title.setTextColor(
                if (hasTitle) {
                    DiaryColor.of(requireContext()).ink
                } else {
                    DiaryColor.of(requireContext()).muted
                },
            )
            val excerpt = diary.plainText.replace(WHITESPACE, " ").trim()
            row.excerpt.text = excerpt
            row.excerpt.isVisible = excerpt.isNotEmpty()
            val deletedAt = diary.deletedAt?.let(DiaryFormats::dateTime).orEmpty()
            val remaining =
                if (item.remainingDays > 0) {
                    "剩余 ${item.remainingDays} 天"
                } else {
                    "即将清理"
                }
            row.meta.text = "删除于 $deletedAt · $remaining · ${item.categoryName}"
            row.restoreButton.setOnClickListener { viewModel.restore(item) }
            row.purgeButton.setOnClickListener { confirmPurge(item) }
            binding.trashList.addView(row.root)
        }
        val empty = state.items.isEmpty()
        binding.emptyState.isVisible = empty
        binding.clearButton.isEnabled = !empty
        binding.clearButton.alpha = if (empty) 0.35f else 1f
    }

    private fun confirmPurge(item: TrashItem) {
        DiaryUi.confirm(
            context = requireContext(),
            title = "彻底删除这篇日记？",
            message = "删除后无法恢复，正文、格式与插图都会被移除。",
            confirmText = "彻底删除",
            onConfirm = { viewModel.purge(item) },
        )
    }

    private fun confirmClear() {
        val count = viewModel.state.value.items.size
        if (count == 0) return
        DiaryUi.confirm(
            context = requireContext(),
            title = "清空回收站？",
            message = "$count 篇日记将被彻底删除，无法恢复。",
            confirmText = "清空",
            onConfirm = { viewModel.clearAll() },
        )
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

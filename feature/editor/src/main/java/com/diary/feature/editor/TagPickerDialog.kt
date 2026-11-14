package com.diary.feature.editor

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.diary.core.database.repository.TagRepository
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.core.model.DiaryLimits
import com.diary.core.model.Tag
import com.diary.feature.editor.databinding.DialogTagPickerBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 标签选择弹层：点选已有标签即时切换，可新建标签；点“完成”把选中集合回传给编辑页。
 * 新建标签是全局操作，立即落库并刷新网格；绑定关系由编辑页统一提交。
 */
@AndroidEntryPoint
class TagPickerDialog : BottomSheetDialogFragment() {
    @Inject
    lateinit var tagRepository: TagRepository

    private var _binding: DialogTagPickerBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val selected = LinkedHashSet<Long>()
    private var tags: List<Tag> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogTagPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        selected.clear()
        selected += requireArguments().getLongArray(ARG_SELECTED)?.toList().orEmpty()
        updateHint()

        binding.createButton.setOnClickListener { createTag() }
        binding.tagInput.doAfterTextChanged { updateHint() }
        binding.doneButton.setOnClickListener { submit() }

        loadTags()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadTags() {
        viewLifecycleOwner.lifecycleScope.launch {
            tags = tagRepository.getAll()
            renderGrid()
        }
    }

    private fun renderGrid() {
        binding.tagGrid.removeAllViews()
        binding.emptyNote.isVisible = tags.isEmpty()
        if (tags.isEmpty()) return
        val palette = DiaryColor.of(requireContext())

        tags.forEach { tag ->
            val chip =
                TextView(requireContext()).apply {
                    text = tag.name
                    textSize = 13f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    minHeight = DiaryUi.dp(requireContext(), 34)
                    isSelected = tag.id in selected
                    setTextColor(if (isSelected) palette.bg else palette.ink)
                    setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                    background =
                        ContextCompat.getDrawable(
                            requireContext(),
                            if (isSelected) {
                                com.diary.core.designsystem.R.drawable.ds_bg_ink
                            } else {
                                com.diary.core.designsystem.R.drawable.ds_bg_chip
                            },
                        )
                    setOnClickListener { toggle(tag) }
                }
            val params =
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = DiaryUi.dp(requireContext(), 34)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    setMargins(0, 0, DiaryUi.dp(requireContext(), 8), DiaryUi.dp(requireContext(), 8))
                }
            binding.tagGrid.addView(chip, params)
        }
        // 占位，保证最后一行左对齐而不是被拉伸。
        val remainder = tags.size % binding.tagGrid.columnCount
        if (remainder != 0) {
            repeat(binding.tagGrid.columnCount - remainder) {
                val spacer = View(requireContext())
                val params =
                    GridLayout.LayoutParams().apply {
                        width = 0
                        height = DiaryUi.dp(requireContext(), 34)
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
                    }
                binding.tagGrid.addView(spacer, params)
            }
        }
    }

    private fun toggle(tag: Tag) {
        if (tag.id in selected) {
            selected -= tag.id
        } else {
            if (selected.size >= DiaryLimits.MAX_TAGS_PER_DIARY) {
                DiaryUi.toast(
                    requireContext(),
                    "每篇最多 ${DiaryLimits.MAX_TAGS_PER_DIARY} 个标签",
                )
                return
            }
            selected += tag.id
        }
        updateHint()
        renderGrid()
    }

    private fun createTag() {
        val name = binding.tagInput.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            binding.tagInput.requestFocus()
            return
        }
        binding.createButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = tagRepository.createTag(name)) {
                is TagRepository.TagResult.Success -> {
                    binding.tagInput.setText("")
                    if (selected.size < DiaryLimits.MAX_TAGS_PER_DIARY) {
                        selected += result.tag.id
                    }
                    tags = tagRepository.getAll()
                    renderGrid()
                    updateHint()
                }

                TagRepository.TagResult.NameTooLong ->
                    DiaryUi.toast(
                        requireContext(),
                        "标签最多 ${DiaryLimits.MAX_TAG_NAME_LENGTH} 个字",
                    )

                TagRepository.TagResult.LimitReached ->
                    DiaryUi.toast(
                        requireContext(),
                        "标签库已满（${DiaryLimits.MAX_TAGS} 个）",
                    )

                else -> Unit
            }
            binding.createButton.isEnabled = true
        }
    }

    private fun updateHint() {
        val typed = binding.tagInput.text?.toString().orEmpty().trim()
        binding.tagHint.text =
            when {
                typed.isNotEmpty() -> "点“新建”创建「$typed」，每篇最多 ${DiaryLimits.MAX_TAGS_PER_DIARY} 个"
                selected.isEmpty() -> "选择或新建标签，可用于搜索页筛选"
                else -> "已选 ${selected.size}/${DiaryLimits.MAX_TAGS_PER_DIARY} 个标签"
            }
    }

    private fun submit() {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(ARG_SELECTED to selected.toLongArray()),
        )
        dismiss()
    }

    companion object {
        const val RESULT_KEY = "tag_pick_result"
        const val ARG_SELECTED = "arg_selected"

        fun newInstance(selectedIds: List<Long>): TagPickerDialog =
            TagPickerDialog().apply {
                arguments = bundleOf(ARG_SELECTED to selectedIds.toLongArray())
            }
    }
}

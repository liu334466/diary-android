package com.diary.feature.diarylist

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.diary.core.designsystem.DiaryUi
import com.diary.feature.diarylist.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 首页：分类横向标签、日记列表、空态与新建入口。
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: HomeViewModel by viewModels()
    private lateinit var adapter: DiaryAdapter
    private var chipSignature: List<Any> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        adapter =
            DiaryAdapter { diary ->
                findNavController().navigate(Uri.parse("diary://reader/${diary.id}"))
            }
        binding.diaryList.layoutManager = LinearLayoutManager(requireContext())
        binding.diaryList.setHasFixedSize(true)
        binding.diaryList.adapter = adapter
        binding.newButton.setOnClickListener { viewModel.createDiary() }
        binding.emptyAction.setOnClickListener { viewModel.createDiary() }
        binding.searchButton.setOnClickListener {
            findNavController().navigate(Uri.parse("diary://search"))
        }
        binding.calendarButton.setOnClickListener {
            findNavController().navigate(Uri.parse("diary://calendar"))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.openEditor.collect { diaryId ->
                        findNavController().navigate(Uri.parse("diary://editor/$diaryId"))
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        binding.diaryList.adapter = null
        chipSignature = emptyList()
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: HomeUiState) {
        if (state.loading) return
        renderChips(state)
        adapter.submit(state.diaries, state.categoryNames, tags = state.tagNames)
        binding.emptyState.isVisible = state.diaries.isEmpty()
        binding.diaryList.isVisible = state.diaries.isNotEmpty()
        binding.emptyTitle.text = "「${state.selectedCategoryName}」还没有日记"
    }

    private fun renderChips(state: HomeUiState) {
        // 分类与选中项未变化时跳过重建，避免每次数据刷新都重新创建标签视图。
        val signature: List<Any> =
            buildList {
                add(state.selectedCategoryId)
                state.categories.forEach { category ->
                    add(category.id)
                    add(category.name)
                }
            }
        if (signature == chipSignature) return
        chipSignature = signature

        binding.chips.removeAllViews()
        addChip("全部", 0L, state.selectedCategoryId == 0L)
        state.categories.forEach { category ->
            addChip(category.name, category.id, state.selectedCategoryId == category.id)
        }
    }

    private fun addChip(
        label: String,
        categoryId: Long,
        selected: Boolean,
    ) {
        val chip =
            TextView(requireContext()).apply {
                text = label
                textSize = 13.5f
                gravity = Gravity.CENTER
                isSelected = selected
                setTextColor(
                    androidx.core.content.ContextCompat.getColorStateList(
                        requireContext(),
                        com.diary.core.designsystem.R.color.ds_chip_text_color,
                    ),
                )
                background =
                    androidx.core.content.ContextCompat.getDrawable(
                        requireContext(),
                        com.diary.core.designsystem.R.drawable.ds_bg_chip,
                    )
                val params =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        DiaryUi.dp(requireContext(), 32),
                    )
                params.marginEnd = DiaryUi.dp(requireContext(), 8)
                layoutParams = params
                setPadding(
                    DiaryUi.dp(requireContext(), 14),
                    0,
                    DiaryUi.dp(requireContext(), 14),
                    0,
                )
                setOnClickListener { viewModel.selectCategory(categoryId) }
            }
        binding.chips.addView(chip)
    }
}

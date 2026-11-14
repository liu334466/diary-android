package com.diary.feature.diarylist

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.feature.diarylist.databinding.FragmentSearchBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 全文搜索页：输入防抖后走 FTS 索引检索，结果点击进入阅读页。
 */
@AndroidEntryPoint
class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: DiaryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        adapter =
            DiaryAdapter { diary ->
                hideKeyboard()
                findNavController().navigate(Uri.parse("diary://reader/${diary.id}"))
            }
        binding.resultList.layoutManager = LinearLayoutManager(requireContext())
        binding.resultList.setHasFixedSize(true)
        binding.resultList.adapter = adapter

        binding.backButton.setOnClickListener {
            hideKeyboard()
            findNavController().navigateUp()
        }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
            viewModel.clear()
            binding.searchInput.requestFocus()
        }
        binding.searchInput.doAfterTextChanged { text ->
            val value = text?.toString().orEmpty()
            binding.clearButton.isVisible = value.isNotEmpty()
            viewModel.onQueryChange(value)
        }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.searchInput.requestFocus()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        binding.resultList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: SearchUiState) {
        val keyword = state.query.trim()
        renderTagFilter(state)
        adapter.submit(state.results, state.categoryNames, keyword.ifEmpty { null }, state.tagNames)

        val showEmpty = state.results.isEmpty()
        binding.emptyState.isVisible = showEmpty
        binding.resultList.isVisible = !showEmpty
        binding.resultCount.isVisible = state.hasSearched
        binding.resultCount.text = "找到 ${state.results.size} 篇"

        if (showEmpty) {
            when {
                keyword.isNotEmpty() -> {
                    binding.emptyTitle.text = "没有找到「$keyword」"
                    binding.emptyDesc.text = "换个关键词试试，标题与正文都会参与搜索。"
                }

                state.selectedTagId != null -> {
                    binding.emptyTitle.text = "「${state.selectedTagName}」下还没有日记"
                    binding.emptyDesc.text = "在编辑页给日记添加标签后，会出现在这里。"
                }

                else -> {
                    binding.emptyTitle.text = "搜索标题与正文"
                    binding.emptyDesc.text = "输入关键词即可查找全部日记，\n也可以用标签筛选，回收站中的内容不会被搜到。"
                }
            }
        }
    }

    /**
     * 标签筛选条：始终保留「全部」，其余来自标签库，选中态用深色底。
     */
    private fun renderTagFilter(state: SearchUiState) {
        val hasTags = state.tags.isNotEmpty()
        binding.tagFilterScroll.isVisible = hasTags
        binding.tagRow.removeAllViews()
        if (!hasTags) return

        addTagChip("全部", state.selectedTagId == null) { viewModel.selectTag(null) }
        state.tags.forEach { tag ->
            addTagChip(tag.name, state.selectedTagId == tag.id) { viewModel.selectTag(tag.id) }
        }
    }

    private fun addTagChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val context = requireContext()
        val palette = DiaryColor.of(context)
        val chip =
            TextView(context).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(
                    DiaryUi.dp(context, 12),
                    0,
                    DiaryUi.dp(context, 12),
                    0,
                )
                setTextColor(if (selected) palette.bg else palette.ink)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                background =
                    ContextCompat.getDrawable(
                        context,
                        if (selected) {
                            com.diary.core.designsystem.R.drawable.ds_bg_ink
                        } else {
                            com.diary.core.designsystem.R.drawable.ds_bg_chip
                        },
                    )
                setOnClickListener { onClick() }
            }
        val params =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                DiaryUi.dp(context, 30),
            ).apply { marginEnd = DiaryUi.dp(context, 8) }
        binding.tagRow.addView(chip, params)
    }

    private fun hideKeyboard() {
        val inputMethodManager = requireContext().getSystemService<InputMethodManager>()
        inputMethodManager?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }
}

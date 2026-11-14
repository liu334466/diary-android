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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.diary.core.designsystem.DiaryColor
import com.diary.feature.diarylist.databinding.FragmentCalendarBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 日历视图：按月网格标记有日记的日期，点选后查看当天日记。
 */
@AndroidEntryPoint
class CalendarFragment : Fragment() {
    private var _binding: FragmentCalendarBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: CalendarViewModel by viewModels()
    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var diaryAdapter: DiaryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCalendarBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        binding.backButton.setOnClickListener { findNavController().navigateUp() }
        binding.prevButton.setOnClickListener { viewModel.showPreviousMonth() }
        binding.nextButton.setOnClickListener { viewModel.showNextMonth() }

        calendarAdapter = CalendarAdapter { date -> viewModel.selectDate(date) }
        binding.calendarGrid.layoutManager = GridLayoutManager(requireContext(), COLUMNS)
        binding.calendarGrid.adapter = calendarAdapter

        diaryAdapter =
            DiaryAdapter { diary ->
                findNavController().navigate(Uri.parse("diary://reader/${diary.id}"))
            }
        binding.diaryList.layoutManager = LinearLayoutManager(requireContext())
        binding.diaryList.setHasFixedSize(true)
        binding.diaryList.adapter = diaryAdapter

        renderWeekdays()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onDestroyView() {
        binding.calendarGrid.adapter = null
        binding.diaryList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun render(state: CalendarUiState) {
        if (state.loading) return
        binding.monthTitle.text = state.monthTitle
        binding.monthSummary.text =
            if (state.monthTotal > 0) {
                "本月 ${state.monthTotal} 篇"
            } else {
                "本月还没有日记"
            }
        binding.selectedTitle.text = state.selectedTitle
        calendarAdapter.submitList(state.days)
        diaryAdapter.submit(state.selectedDiaries, emptyMap())
        binding.emptyHint.isVisible = state.selectedDiaries.isEmpty()
        binding.diaryList.isVisible = state.selectedDiaries.isNotEmpty()
    }

    /** 星期表头：周一起头，与格子排列保持一致。 */
    private fun renderWeekdays() {
        val labels = listOf("一", "二", "三", "四", "五", "六", "日")
        val palette = DiaryColor.of(requireContext())
        binding.weekdayRow.removeAllViews()
        labels.forEach { label ->
            val view =
                TextView(requireContext()).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(palette.muted)
                }
            binding.weekdayRow.addView(
                view,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private companion object {
        const val COLUMNS = 7
    }
}

package com.diary.feature.diarylist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.model.Diary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

/**
 * 日历格子。跨月的补白格也保留日期，便于统一处理点击与高亮。
 */
private val WEEKDAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

data class CalendarDay(
    val date: LocalDate,
    val inMonth: Boolean,
    val diaries: Int,
    val isToday: Boolean,
    val isSelected: Boolean,
)

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val days: List<CalendarDay> = emptyList(),
    val selectedDiaries: List<Diary> = emptyList(),
    val monthTotal: Int = 0,
    val loading: Boolean = true,
) {
    val monthTitle: String
        get() = "${month.year} 年 ${month.monthValue} 月"

    val selectedTitle: String
        get() = "${selectedDate.monthValue} 月 ${selectedDate.dayOfMonth} 日 · 周${weekdayName(selectedDate)}"

    private fun weekdayName(date: LocalDate): String = WEEKDAY_NAMES[date.dayOfWeek.value - 1]
}

/**
 * 日历视图：按月加载日记，标记有内容的日期，点选查看当天日记。
 */
@HiltViewModel
class CalendarViewModel
    @Inject
    constructor(
        private val diaryRepository: DiaryRepository,
    ) : ViewModel() {
        private val zone: ZoneId = ZoneId.systemDefault()
        private val today: LocalDate = LocalDate.now(zone)

        private val month = MutableStateFlow(YearMonth.from(today))
        private val selectedDate = MutableStateFlow(today)

        private val monthDiaries =
            month.flatMapLatest { target ->
                diaryRepository.observeBetween(startOf(target), startOf(target.plusMonths(1)))
            }

        val state: StateFlow<CalendarUiState> =
            combine(
                month,
                selectedDate,
                monthDiaries,
            ) { target, selected, diaries ->
                val byDate = diaries.groupBy { dateOf(it.createdAt) }
                CalendarUiState(
                    month = target,
                    selectedDate = selected,
                    days =
                        buildCalendarDays(
                            month = target,
                            selected = selected,
                            today = today,
                            counts = byDate.mapValues { it.value.size },
                        ),
                    selectedDiaries = byDate[selected].orEmpty(),
                    monthTotal = diaries.size,
                    loading = false,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

        fun showPreviousMonth() = shiftMonth(-1)

        fun showNextMonth() = shiftMonth(1)

        fun selectDate(date: LocalDate) {
            selectedDate.value = date
        }

        /**
         * 翻月时把选中日期落到目标月的今天或 1 号，避免选中项停在上个月。
         */
        private fun shiftMonth(delta: Long) {
            val target = month.value.plusMonths(delta)
            month.value = target
            selectedDate.value =
                if (YearMonth.from(today) == target) {
                    today
                } else {
                    target.atDay(1)
                }
        }

        private fun startOf(month: YearMonth): Long = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

        private fun dateOf(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

        private companion object {
            /** 固定 6 行 7 列，翻月时高度不跳动。 */
            const val GRID_CELLS = 42
        }
    }

/**
 * 生成月历格子：以周一为一周起点，向前补白对齐，固定 42 格。
 * 抽成纯函数便于对跨月、闰月与统计映射做单测。
 */
internal fun buildCalendarDays(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    counts: Map<LocalDate, Int>,
): List<CalendarDay> {
    val firstOfMonth = month.atDay(1)
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
    return (0 until 42).map { index ->
        val date = gridStart.plusDays(index.toLong())
        CalendarDay(
            date = date,
            inMonth = date.month == month.month && date.year == month.year,
            diaries = counts[date] ?: 0,
            isToday = date == today,
            isSelected = date == selected,
        )
    }
}

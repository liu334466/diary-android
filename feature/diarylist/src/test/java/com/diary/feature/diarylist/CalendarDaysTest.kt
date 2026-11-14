package com.diary.feature.diarylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月历格子生成：周一起头、跨月补白、统计与选中/今天标记。
 */
class CalendarDaysTest {
    @Test
    fun `固定 42 格且从所在周的周一开始`() {
        // 2026-11-01 是周日，网格要从 10-26（周一）开始。
        val days =
            buildCalendarDays(
                month = YearMonth.of(2026, 11),
                selected = LocalDate.of(2026, 11, 14),
                today = LocalDate.of(2026, 11, 14),
                counts = emptyMap(),
            )
        assertEquals(42, days.size)
        assertEquals(LocalDate.of(2026, 10, 26), days.first().date)
        assertEquals(30, days.count { it.inMonth })
        assertTrue(days.first().date.dayOfWeek.value == 1)
    }

    @Test
    fun `跨越闰年二月时补白格正确`() {
        val days =
            buildCalendarDays(
                month = YearMonth.of(2028, 2),
                selected = LocalDate.of(2028, 2, 1),
                today = LocalDate.of(2028, 2, 20),
                counts = emptyMap(),
            )
        // 2028-02-01 是周二，网格从 01-31（周一）开始；闰年二月共 29 天。
        assertEquals(LocalDate.of(2028, 1, 31), days.first().date)
        assertEquals(29, days.count { it.inMonth })
        assertEquals(29, days.last { it.inMonth }.date.dayOfMonth)
    }

    @Test
    fun `统计数量与今天选中标记落在正确日期`() {
        val selected = LocalDate.of(2026, 11, 14)
        val counts =
            mapOf(
                LocalDate.of(2026, 11, 14) to 3,
                LocalDate.of(2026, 11, 20) to 1,
            )
        val days =
            buildCalendarDays(
                month = YearMonth.of(2026, 11),
                selected = selected,
                today = LocalDate.of(2026, 11, 2),
                counts = counts,
            )
        assertEquals(3, days.first { it.date == selected }.diaries)
        assertTrue(days.first { it.date == selected }.isSelected)
        assertTrue(days.first { it.date == LocalDate.of(2026, 11, 2) }.isToday)
        assertFalse(days.first { it.date == LocalDate.of(2026, 11, 20) }.isSelected)
        assertEquals(1, days.first { it.date == LocalDate.of(2026, 11, 20) }.diaries)
        assertEquals(0, days.first { it.date == LocalDate.of(2026, 11, 3) }.diaries)
    }
}

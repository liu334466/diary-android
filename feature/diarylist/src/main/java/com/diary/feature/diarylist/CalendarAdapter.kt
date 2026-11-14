package com.diary.feature.diarylist

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.diary.core.designsystem.DiaryColor
import com.diary.core.designsystem.DiaryUi
import com.diary.feature.diarylist.databinding.ItemCalendarDayBinding
import java.time.LocalDate

/**
 * 月历格子：日期数字、有日记时的小圆点、今天描边、选中实心。
 */
class CalendarAdapter(
    private val onClick: (LocalDate) -> Unit,
) : ListAdapter<CalendarDay, CalendarAdapter.DayViewHolder>(Diff) {
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): DayViewHolder =
        DayViewHolder(
            ItemCalendarDayBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

    override fun onBindViewHolder(
        holder: DayViewHolder,
        position: Int,
    ) {
        holder.bind(getItem(position))
    }

    inner class DayViewHolder(
        private val binding: ItemCalendarDayBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(day: CalendarDay) {
            val context = binding.root.context
            val palette = DiaryColor.of(context)
            val circle = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            val textColor =
                when {
                    day.isSelected -> palette.bg
                    !day.inMonth -> palette.muted
                    else -> palette.ink
                }
            when {
                day.isSelected -> circle.setColor(palette.ink)
                day.isToday -> {
                    circle.setColor(android.graphics.Color.TRANSPARENT)
                    circle.setStroke(DiaryUi.dp(context, 1), palette.ink)
                }
                else -> circle.setColor(android.graphics.Color.TRANSPARENT)
            }
            binding.dayText.background = circle
            binding.dayText.setTextColor(textColor)
            binding.dayText.text = day.date.dayOfMonth.toString()
            binding.dayText.setTypeface(null, if (day.isSelected) Typeface.BOLD else Typeface.NORMAL)

            val showDot = day.diaries > 0
            binding.dot.visibility = if (showDot) View.VISIBLE else View.INVISIBLE
            if (showDot) {
                val dotColor =
                    when {
                        day.isSelected -> palette.bg
                        day.inMonth -> palette.ink
                        else -> palette.muted
                    }
                binding.dot.background =
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(dotColor)
                    }
            }

            binding.root.setOnClickListener { onClick(day.date) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<CalendarDay>() {
        override fun areItemsTheSame(
            oldItem: CalendarDay,
            newItem: CalendarDay,
        ): Boolean = oldItem.date == newItem.date

        override fun areContentsTheSame(
            oldItem: CalendarDay,
            newItem: CalendarDay,
        ): Boolean = oldItem == newItem
    }
}

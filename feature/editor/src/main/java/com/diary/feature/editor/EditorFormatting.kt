package com.diary.feature.editor

import android.graphics.Typeface
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.diary.core.richtext.Align
import com.diary.core.richtext.FontIdSpan
import com.diary.core.richtext.ListType
import com.diary.core.richtext.OrderedListSpan
import com.diary.core.richtext.SizeLevelSpan

/**
 * 编辑页格式操作：全部作用于当前选区或光标所在段落，不触碰段落之外的内容。
 */
object EditorFormatting {
    const val PARAGRAPH_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_PARAGRAPH

    fun paragraphRange(
        text: CharSequence,
        offset: Int,
    ): IntRange {
        if (text.isEmpty()) return 0 until 0
        val safe = offset.coerceIn(0, text.length)
        val start =
            text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0))
                .let { if (it < 0 || safe == 0) 0 else it + 1 }
        val end = text.indexOf('\n', safe).let { if (it < 0) text.length else it }
        return start until end
    }

    fun applyBold(
        text: Spannable,
        start: Int,
        end: Int,
        enable: Boolean,
    ) = applyStyleSpan(text, start, end, Typeface.BOLD, enable)

    fun applyItalic(
        text: Spannable,
        start: Int,
        end: Int,
        enable: Boolean,
    ) = applyStyleSpan(text, start, end, Typeface.ITALIC, enable)

    fun applyUnderline(
        text: Spannable,
        start: Int,
        end: Int,
        enable: Boolean,
    ) {
        if (start >= end) return
        removeIntersecting<UnderlineSpan>(text, start, end)
        if (enable) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun applyColor(
        text: Spannable,
        start: Int,
        end: Int,
        color: Int,
    ) {
        if (start >= end) return
        removeIntersecting<ForegroundColorSpan>(text, start, end)
        text.setSpan(
            ForegroundColorSpan(color),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    fun applyFont(
        text: Spannable,
        start: Int,
        end: Int,
        fontId: Long?,
        typeface: Typeface?,
    ) {
        if (start >= end) return
        removeIntersecting<FontIdSpan>(text, start, end)
        if (fontId != null && typeface != null) {
            text.setSpan(
                FontIdSpan(fontId, typeface),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    fun applySizeLevel(
        text: Spannable,
        start: Int,
        end: Int,
        level: Int,
        sizeSp: Float,
    ) {
        if (start >= end) return
        removeIntersecting<SizeLevelSpan>(text, start, end)
        text.setSpan(
            SizeLevelSpan(level, sizeSp),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

    /**
     * 段落对齐循环：左 → 中 → 右 → 左。
     */
    fun cycleAlignment(
        text: Editable,
        cursor: Int,
    ): String {
        val range = paragraphRange(text, cursor)
        if (range.isEmpty()) return Align.LEFT
        val current =
            text.getSpans(range.first, range.last + 1, AlignmentSpan::class.java)
                .lastOrNull()?.alignment
        val next =
            when (current) {
                Layout.Alignment.ALIGN_CENTER -> Align.RIGHT
                Layout.Alignment.ALIGN_OPPOSITE -> Align.LEFT
                else -> Align.CENTER
            }
        text.getSpans(range.first, range.last + 1, AlignmentSpan::class.java)
            .forEach { text.removeSpan(it) }
        if (next != Align.LEFT) {
            val alignment =
                when (next) {
                    Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_CENTER
                }
            text.setSpan(
                AlignmentSpan.Standard(alignment),
                range.first,
                range.last + 1,
                PARAGRAPH_FLAGS,
            )
        }
        return next
    }

    /**
     * 列表循环：无 → 无序 → 有序 → 无。
     */
    fun cycleList(
        text: Editable,
        cursor: Int,
        gapPx: Int,
    ): String {
        val range = paragraphRange(text, cursor)
        if (range.isEmpty()) return ListType.NONE
        val queryEnd = range.last + 1
        val current =
            when {
                text.getSpans(range.first, queryEnd, OrderedListSpan::class.java).isNotEmpty() ->
                    ListType.ORDERED
                text.getSpans(range.first, queryEnd, BulletSpan::class.java).isNotEmpty() ->
                    ListType.UNORDERED
                else -> ListType.NONE
            }
        val next =
            when (current) {
                ListType.NONE -> ListType.UNORDERED
                ListType.UNORDERED -> ListType.ORDERED
                else -> ListType.NONE
            }
        text.getSpans(range.first, queryEnd, OrderedListSpan::class.java)
            .forEach { text.removeSpan(it) }
        text.getSpans(range.first, queryEnd, BulletSpan::class.java)
            .forEach { text.removeSpan(it) }
        when (next) {
            ListType.UNORDERED ->
                text.setSpan(
                    BulletSpan(gapPx),
                    range.first,
                    queryEnd,
                    PARAGRAPH_FLAGS,
                )
            ListType.ORDERED ->
                text.setSpan(
                    OrderedListSpan(1, gapPx),
                    range.first,
                    queryEnd,
                    PARAGRAPH_FLAGS,
                )
            else -> Unit
        }
        return next
    }

    /** 清除选区或段落的全部行内格式。 */
    fun clearFormatting(
        text: Editable,
        start: Int,
        end: Int,
    ) {
        val from = if (start == end) paragraphRange(text, start).first else start
        val to = if (start == end) paragraphRange(text, start).last + 1 else end
        if (from >= to) return
        removeIntersecting<StyleSpan>(text, from, to)
        removeIntersecting<UnderlineSpan>(text, from, to)
        removeIntersecting<ForegroundColorSpan>(text, from, to)
        removeIntersecting<FontIdSpan>(text, from, to)
        removeIntersecting<SizeLevelSpan>(text, from, to)
    }

    private fun applyStyleSpan(
        text: Spannable,
        start: Int,
        end: Int,
        style: Int,
        enable: Boolean,
    ) {
        if (start >= end) return
        val existing =
            text.getSpans(start, end, StyleSpan::class.java)
                .filter { it.style and style != 0 }
        existing.forEach { text.removeSpan(it) }
        if (enable) {
            text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inline fun <reified T> removeIntersecting(
        text: Spannable,
        start: Int,
        end: Int,
    ) {
        text.getSpans(start, end, T::class.java).forEach { text.removeSpan(it) }
    }
}

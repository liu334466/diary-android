package com.diary.feature.export

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import com.diary.core.model.FontSizeLevel
import com.diary.core.richtext.RichTextDocument
import com.diary.core.richtext.RichTextSpans
import com.diary.core.richtext.SizeLevelSpan

/**
 * 导出格式。
 */
enum class ExportFormat {
    PDF,
    PNG,
}

/**
 * 导出所需的完整排版素材：正文结构、基准字体字号、字体映射与图片解析。
 * PNG 与 PDF 共用同一份内容，保证两种格式排版口径一致。
 */
data class ExportContent(
    val title: String,
    val meta: String,
    val document: RichTextDocument,
    val baseTypeface: Typeface,
    val baseSizeSp: Float,
    val fonts: Map<Long, Typeface>,
    val imageResolver: (String) -> Drawable?,
) {
    /**
     * 按导出倍率生成 Spannable。
     * L3 字号改写为固定像素，避免同一条日记在不同密度设备上导出结果不一致。
     */
    fun spannable(
        scale: Float,
        listGapPx: Int,
        imageMaxWidthPx: Int,
    ): SpannableStringBuilder {
        val text =
            RichTextSpans.toSpannable(
                document = document,
                baseSizeSp = baseSizeSp * scale,
                listGapPx = listGapPx,
                fontResolver = { fontId -> fonts[fontId] },
                imageResolver = imageResolver,
                imageMaxWidthPx = imageMaxWidthPx,
            )
        text.getSpans(0, text.length, SizeLevelSpan::class.java).forEach { span ->
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            text.removeSpan(span)
            if (start >= 0 && end > start) {
                text.setSpan(
                    AbsoluteSizeSpan((FontSizeLevel.sizeSpOf(span.level) * scale).toInt(), false),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return text
    }
}

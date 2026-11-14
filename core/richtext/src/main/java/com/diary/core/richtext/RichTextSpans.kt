package com.diary.core.richtext

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan

/**
 * 富文本 JSON 与 Android Spannable 的双向桥接，编辑页与阅读页共用，保证 L3 格式一致。
 */
object RichTextSpans {
    private const val PARAGRAPH_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_PARAGRAPH

    /**
     * 文档转 Spannable。
     *
     * @param baseSizeSp 正文基准字号，未被 L3 覆盖的文字使用该字号
     * @param fontResolver 字体 id 转 Typeface，缺失时返回 null 走系统字体
     * @param imageResolver 图片文件名转 Drawable，缺失时跳过图片
     * @param imageMaxWidthPx 图片显示的最大宽度，0 表示按图片原始尺寸
     */
    fun toSpannable(
        document: RichTextDocument,
        baseSizeSp: Float,
        listGapPx: Int,
        fontResolver: (Long) -> Typeface? = { null },
        imageResolver: (String) -> Drawable? = { null },
        imageMaxWidthPx: Int = 0,
        colorMapper: (Int) -> Int = { it },
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        var offset = 0
        var orderedIndex = 0
        document.blocks.forEachIndexed { index, block ->
            if (block.list == ListType.ORDERED) orderedIndex++ else orderedIndex = 0
            if (index > 0) {
                builder.append('\n')
                offset += 1
            }
            val start = offset
            builder.append(block.text)
            val end = start + block.text.length
            offset = end
            if (end > start) {
                applyParagraphSpans(builder, start, end, block, listGapPx, orderedIndex)
                block.spans.forEach { span ->
                    applyInlineSpan(
                        builder,
                        start,
                        end,
                        span,
                        fontResolver,
                        imageResolver,
                        imageMaxWidthPx,
                        colorMapper,
                    )
                }
            }
        }
        return builder
    }

    /**
     * Spannable 转文档。段落以换行切分，行内格式按片段还原。
     */
    fun fromSpannable(text: Spanned): RichTextDocument {
        val blocks = ArrayList<RichTextBlock>()
        var start = 0
        while (true) {
            val newline = text.indexOf('\n', start)
            val end = if (newline < 0) text.length else newline
            blocks += buildBlock(text, start, end)
            if (newline < 0) break
            start = newline + 1
        }
        if (blocks.isEmpty()) blocks += RichTextBlock()
        return RichTextDocument(blocks = blocks)
    }

    /** 统计正文中的插图数量。 */
    fun countImages(text: Spanned): Int {
        if (text.isEmpty()) return 0
        return text.getSpans(0, text.length, ImagePathSpan::class.java).size
    }

    /** 文档引用的全部字体 id。 */
    fun referencedFontIds(document: RichTextDocument): Set<Long> =
        document.blocks.flatMap { block -> block.spans.mapNotNull { it.fontId } }.toSet()

    /**
     * 读取选区（或光标处）的行内格式，用于工具条高亮与混合状态展示。
     */
    fun attributesAt(
        text: Spanned,
        start: Int,
        end: Int,
    ): InlineAttributes {
        if (text.isEmpty()) return InlineAttributes()
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)

        fun covers(span: Any): Boolean {
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            if (spanStart < 0) return false
            return if (from == to) {
                spanStart <= from && from < spanEnd
            } else {
                spanStart < to && spanEnd > from
            }
        }

        val fontIds =
            text.getSpans(0, text.length, FontIdSpan::class.java)
                .filter(::covers).map { it.fontId }.toSet()
        val sizeLevels =
            text.getSpans(0, text.length, SizeLevelSpan::class.java)
                .filter(::covers).map { it.level }.toSet()
        val bold =
            text.getSpans(0, text.length, StyleSpan::class.java)
                .any { covers(it) && it.style and Typeface.BOLD != 0 }
        val italic =
            text.getSpans(0, text.length, StyleSpan::class.java)
                .any { covers(it) && it.style and Typeface.ITALIC != 0 }
        val underline = text.getSpans(0, text.length, UnderlineSpan::class.java).any(::covers)
        return InlineAttributes(
            fontIds = fontIds,
            sizeLevels = sizeLevels,
            bold = bold,
            italic = italic,
            underline = underline,
        )
    }

    private fun applyParagraphSpans(
        builder: SpannableStringBuilder,
        start: Int,
        end: Int,
        block: RichTextBlock,
        listGapPx: Int,
        orderedIndex: Int,
    ) {
        when (block.align) {
            Align.CENTER ->
                builder.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                    start,
                    end,
                    PARAGRAPH_FLAGS,
                )
            Align.RIGHT ->
                builder.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE),
                    start,
                    end,
                    PARAGRAPH_FLAGS,
                )
            Align.JUSTIFY ->
                builder.setSpan(
                    AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL),
                    start,
                    end,
                    PARAGRAPH_FLAGS,
                )
            else -> Unit
        }
        when (block.list) {
            ListType.UNORDERED ->
                builder.setSpan(
                    BulletSpan(listGapPx),
                    start,
                    end,
                    PARAGRAPH_FLAGS,
                )
            ListType.ORDERED ->
                builder.setSpan(
                    OrderedListSpan(orderedIndex.coerceAtLeast(1), listGapPx),
                    start,
                    end,
                    PARAGRAPH_FLAGS,
                )
            else -> Unit
        }
    }

    private fun applyInlineSpan(
        builder: SpannableStringBuilder,
        blockStart: Int,
        blockEnd: Int,
        span: RichTextSpan,
        fontResolver: (Long) -> Typeface?,
        imageResolver: (String) -> Drawable?,
        imageMaxWidthPx: Int,
        colorMapper: (Int) -> Int,
    ) {
        val start = (blockStart + span.start).coerceIn(blockStart, blockEnd)
        val end = (blockStart + span.end).coerceIn(start, blockEnd)
        if (end <= start) return

        span.image?.let { path ->
            if (end - start == 1 && builder[start] == RichTextCodec.IMAGE_PLACEHOLDER) {
                imageResolver(path)?.let { drawable ->
                    builder.setSpan(
                        ImagePathSpan(path, drawable, imageMaxWidthPx),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            return
        }
        span.fontId?.let { fontId ->
            fontResolver(fontId)?.let { typeface ->
                builder.setSpan(
                    FontIdSpan(fontId, typeface),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        span.sizeLevel?.let { level ->
            builder.setSpan(
                SizeLevelSpan(level, com.diary.core.model.FontSizeLevel.sizeSpOf(level)),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (span.bold == true) {
            builder.setSpan(
                StyleSpan(Typeface.BOLD),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (span.italic == true) {
            builder.setSpan(
                StyleSpan(Typeface.ITALIC),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        if (span.underline == true) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        span.color?.let { color ->
            runCatching { android.graphics.Color.parseColor(color) }.getOrNull()?.let { parsed ->
                builder.setSpan(
                    ForegroundColorSpan(colorMapper(parsed)),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun buildBlock(
        text: Spanned,
        start: Int,
        end: Int,
    ): RichTextBlock {
        val blockText = text.subSequence(start, end).toString()
        val queryStart = start
        val queryEnd = if (end > start) end else minOf(start + 1, text.length)

        val alignment =
            if (queryEnd > queryStart) {
                text.getSpans(queryStart, queryEnd, AlignmentSpan::class.java).lastOrNull()?.alignment
            } else {
                null
            }
        val align =
            when (alignment) {
                Layout.Alignment.ALIGN_CENTER -> Align.CENTER
                Layout.Alignment.ALIGN_OPPOSITE -> Align.RIGHT
                else -> Align.LEFT
            }

        val list =
            when {
                queryEnd <= queryStart -> ListType.NONE
                text.getSpans(queryStart, queryEnd, OrderedListSpan::class.java).isNotEmpty() ->
                    ListType.ORDERED
                text.getSpans(queryStart, queryEnd, BulletSpan::class.java).isNotEmpty() ->
                    ListType.UNORDERED
                else -> ListType.NONE
            }

        val spans = ArrayList<RichTextSpan>()

        if (queryEnd > queryStart) {
            text.getSpans(queryStart, queryEnd, StyleSpan::class.java).forEach { style ->
                val range = clip(text, style, start, end) ?: return@forEach
                if (style.style and Typeface.BOLD != 0) {
                    spans += RichTextSpan(range.first, range.second, bold = true)
                }
                if (style.style and Typeface.ITALIC != 0) {
                    spans += RichTextSpan(range.first, range.second, italic = true)
                }
            }
            text.getSpans(queryStart, queryEnd, UnderlineSpan::class.java).forEach { underline ->
                val range = clip(text, underline, start, end) ?: return@forEach
                spans += RichTextSpan(range.first, range.second, underline = true)
            }
            text.getSpans(queryStart, queryEnd, ForegroundColorSpan::class.java).forEach { color ->
                val range = clip(text, color, start, end) ?: return@forEach
                spans +=
                    RichTextSpan(
                        range.first,
                        range.second,
                        color = String.format("#%06X", 0xFFFFFF and color.foregroundColor),
                    )
            }
            text.getSpans(queryStart, queryEnd, FontIdSpan::class.java).forEach { font ->
                val range = clip(text, font, start, end) ?: return@forEach
                spans += RichTextSpan(range.first, range.second, fontId = font.fontId)
            }
            text.getSpans(queryStart, queryEnd, SizeLevelSpan::class.java).forEach { size ->
                val range = clip(text, size, start, end) ?: return@forEach
                spans += RichTextSpan(range.first, range.second, sizeLevel = size.level)
            }
            text.getSpans(queryStart, queryEnd, ImagePathSpan::class.java).forEach { image ->
                val range = clip(text, image, start, end) ?: return@forEach
                spans += RichTextSpan(range.first, range.second, image = image.path)
            }
        }

        return RichTextBlock(
            align = align,
            list = list,
            text = blockText,
            spans = spans.sortedBy { it.start },
        )
    }

    private fun clip(
        text: Spanned,
        span: Any,
        blockStart: Int,
        blockEnd: Int,
    ): Pair<Int, Int>? {
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        if (spanStart < 0) return null
        val start = maxOf(spanStart, blockStart)
        val end = minOf(spanEnd, blockEnd)
        if (end <= start) return null
        return (start - blockStart) to (end - blockStart)
    }
}

/**
 * 行内格式快照。集合大小为 0 表示继承基准样式，大于 1 表示选区混合。
 */
data class InlineAttributes(
    val fontIds: Set<Long> = emptySet(),
    val sizeLevels: Set<Int> = emptySet(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
) {
    val fontMixed: Boolean get() = fontIds.size > 1
    val sizeMixed: Boolean get() = sizeLevels.size > 1

    val singleFontId: Long? get() = fontIds.singleOrNull()
    val singleSizeLevel: Int? get() = sizeLevels.singleOrNull()
}

/** 记录字体 id 的自定义 TypefaceSpan，序列化时可还原 fontId。 */
class FontIdSpan(
    val fontId: Long,
    private val typeface: Typeface,
) : CharacterStyle() {
    override fun updateDrawState(tp: android.text.TextPaint) {
        val style = tp.typeface?.style ?: Typeface.NORMAL
        tp.typeface = Typeface.create(typeface, style)
    }
}

/** 记录字号档位的 AbsoluteSizeSpan，渲染使用 sp。 */
class SizeLevelSpan(
    val level: Int,
    sizeSp: Float,
) : AbsoluteSizeSpan(sizeSp.toInt(), true)

/** 记录图片文件名的 ImageSpan，序列化时写回 image 字段。 */
class ImagePathSpan(
    val path: String,
    drawable: Drawable,
    maxWidthPx: Int = 0,
) : ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM) {
    init {
        // ImageSpan(Drawable) 不会设置 bounds，bounds 为空时图片既不占位也不绘制。
        val naturalWidth = drawable.intrinsicWidth
        val naturalHeight = drawable.intrinsicHeight
        if (naturalWidth > 0 && naturalHeight > 0) {
            val width = if (maxWidthPx > 0) minOf(naturalWidth, maxWidthPx) else naturalWidth
            val height = (naturalHeight.toFloat() * width / naturalWidth).toInt().coerceAtLeast(1)
            drawable.setBounds(0, 0, width, height)
        }
    }
}

/**
 * 有序列表段落：在左边距绘制 “1.”，与无序列表共用缩进宽度。
 */
class OrderedListSpan(
    private val number: Int,
    private val gapWidth: Int,
) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean): Int = gapWidth

    override fun drawLeadingMargin(
        canvas: Canvas,
        paint: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {
        if (!first) return
        val originalStyle = paint.typeface
        val originalColor = paint.color
        paint.isAntiAlias = true
        canvas.drawText("$number.", x.toFloat(), baseline.toFloat(), paint)
        paint.typeface = originalStyle
        paint.color = originalColor
    }
}

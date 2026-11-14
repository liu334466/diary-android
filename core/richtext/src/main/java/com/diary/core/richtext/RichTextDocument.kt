package com.diary.core.richtext

/**
 * 富文本正文，序列化结构见 PRD 4.5。
 *
 * @property version 数据结构版本号
 * @property blocks 段落数组
 * @property raw 未知高版本时保留的原始 JSON，用于避免重新保存导致数据丢失
 */
data class RichTextDocument(
    val version: Int = RichTextCodec.CURRENT_VERSION,
    val blocks: List<RichTextBlock> = emptyList(),
    val raw: String? = null,
)

/**
 * 段落块。文本存放在 [text]，格式信息由 [spans] 描述。
 */
data class RichTextBlock(
    val align: String = Align.LEFT,
    val list: String = ListType.NONE,
    val text: String = "",
    val spans: List<RichTextSpan> = emptyList(),
)

/**
 * 格式片段，[start]/[end] 为段落文本内偏移，左闭右开。
 * 可空布尔值用于区分“未设置”与“显式关闭”，选择器据此展示混合状态。
 */
data class RichTextSpan(
    val start: Int,
    val end: Int,
    val fontId: Long? = null,
    val sizeLevel: Int? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val color: String? = null,
    /** 插图文件名，非空时该片段代表一张图片（正文中以占位符标记）。 */
    val image: String? = null,
) {
    init {
        require(end > start) { "格式片段必须满足 end > start" }
    }
}

object Align {
    const val LEFT = "left"
    const val CENTER = "center"
    const val RIGHT = "right"
    const val JUSTIFY = "justify"
    val ALL = setOf(LEFT, CENTER, RIGHT, JUSTIFY)
}

object ListType {
    const val NONE = "none"
    const val ORDERED = "ordered"
    const val UNORDERED = "unordered"
    val ALL = setOf(NONE, ORDERED, UNORDERED)
}

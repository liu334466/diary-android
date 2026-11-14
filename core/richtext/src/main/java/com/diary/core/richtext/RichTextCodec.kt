package com.diary.core.richtext

import org.json.JSONArray
import org.json.JSONObject

/**
 * 富文本 JSON 编解码。
 *
 * 降级策略（PRD 4.5 / 5.3）：
 * - 任何解析异常都不抛出，返回空文档，保证旧日记仍可打开；
 * - 未知字段忽略，不阻断已知字段读取；
 * - 版本号高于当前版本时保留原始 JSON，再次编码时原样返回，避免升级后旧版本破坏新数据；
 * - 偏移、字号档位等越界值收敛到合法范围，非法片段直接丢弃。
 */
object RichTextCodec {
    const val CURRENT_VERSION = 1

    /** 正文中的插图占位字符，与 Android ImageSpan 占位一致。 */
    const val IMAGE_PLACEHOLDER: Char = '\uFFFC'

    private val colorRegex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    fun emptyDocument(): RichTextDocument = RichTextDocument(blocks = listOf(RichTextBlock()))

    fun emptyJson(): String = encode(emptyDocument())

    fun decode(json: String?): RichTextDocument {
        if (json.isNullOrBlank()) return emptyDocument()
        return try {
            val root = JSONObject(json)
            val version = root.optInt("v", CURRENT_VERSION)
            val blocks = parseBlocks(root.optJSONArray("blocks"))
            RichTextDocument(
                version = version,
                blocks = if (blocks.isEmpty()) listOf(RichTextBlock()) else blocks,
                raw = if (version > CURRENT_VERSION) json else null,
            )
        } catch (_: Exception) {
            // 结构损坏时返回空正文，避免崩溃；后续保存会覆盖损坏数据。
            RichTextDocument(blocks = listOf(RichTextBlock()), raw = null)
        }
    }

    fun encode(document: RichTextDocument): String {
        // 未知高版本原样返回，避免当前版本无法识别的字段被覆盖。
        document.raw?.let { return it }

        val root = JSONObject()
        root.put("v", document.version)
        val blocks = JSONArray()
        document.blocks.forEach { block ->
            blocks.put(encodeBlock(block))
        }
        root.put("blocks", blocks)
        return root.toString()
    }

    /**
     * 移除正文中引用指定字体的格式片段，其余格式保持不变。
     * 结构损坏或未知高版本无法安全改写，直接返回原文，避免破坏数据。
     */
    fun removeFontSpans(
        json: String,
        fontId: Long,
    ): String {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return json
        if (root.optInt("v", CURRENT_VERSION) > CURRENT_VERSION) return json
        val document = decode(json)
        val cleaned =
            document.copy(
                blocks =
                    document.blocks.map { block ->
                        block.copy(spans = block.spans.filterNot { it.fontId == fontId })
                    },
            )
        return encode(cleaned)
    }

    fun plainText(document: RichTextDocument): String =
        document.blocks.joinToString("\n") { it.text }.replace(IMAGE_PLACEHOLDER.toString(), "")

    /** 正文字符数：含标点、不含空白字符。 */
    fun charCount(text: String): Int = text.count { !it.isWhitespace() }

    private fun parseBlocks(array: JSONArray?): List<RichTextBlock> {
        if (array == null) return emptyList()
        val result = ArrayList<RichTextBlock>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("text", "")
            val align = item.optString("align", Align.LEFT).takeIf { it in Align.ALL } ?: Align.LEFT
            val list =
                item.optString("list", ListType.NONE).takeIf { it in ListType.ALL }
                    ?: ListType.NONE
            result +=
                RichTextBlock(
                    align = align,
                    list = list,
                    text = text,
                    spans = parseSpans(item.optJSONArray("spans"), text.length),
                )
        }
        return result
    }

    private fun parseSpans(
        array: JSONArray?,
        textLength: Int,
    ): List<RichTextSpan> {
        if (array == null) return emptyList()
        val result = ArrayList<RichTextSpan>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val span = parseSpan(item, textLength) ?: continue
            result += span
        }
        return result
    }

    private fun parseSpan(
        item: JSONObject,
        textLength: Int,
    ): RichTextSpan? {
        if (!item.has("s") || !item.has("e")) return null
        val start = item.optInt("s", -1)
        var end = item.optInt("e", -1)
        if (start < 0 || end <= start) return null
        if (textLength > 0) {
            end = minOf(end, textLength)
            if (end <= start) return null
        }
        val fontId = item.optLong("fontId", 0L).takeIf { item.has("fontId") && it > 0L }
        val sizeLevel =
            if (item.has("sizeLevel")) {
                item.optInt("sizeLevel", -1).takeIf { it in 0..9 }
            } else {
                null
            }
        val color =
            item.optString("color", "")
                .takeIf { colorRegex.matches(it) }
        val image = item.optString("image", "").takeIf { it.isNotBlank() }

        return RichTextSpan(
            start = start,
            end = end,
            fontId = fontId,
            sizeLevel = sizeLevel,
            bold = item.optBooleanOrNull("bold"),
            italic = item.optBooleanOrNull("italic"),
            underline = item.optBooleanOrNull("underline"),
            color = color,
            image = image,
        )
    }

    private fun encodeBlock(block: RichTextBlock): JSONObject {
        val json = JSONObject()
        json.put("align", block.align.takeIf { it in Align.ALL } ?: Align.LEFT)
        json.put("list", block.list.takeIf { it in ListType.ALL } ?: ListType.NONE)
        json.put("text", block.text)
        val spans = JSONArray()
        block.spans.forEach { span ->
            if (span.start < 0 || span.end <= span.start) return@forEach
            spans.put(encodeSpan(span))
        }
        json.put("spans", spans)
        return json
    }

    private fun encodeSpan(span: RichTextSpan): JSONObject {
        val json = JSONObject()
        json.put("s", span.start)
        json.put("e", span.end)
        span.fontId?.takeIf { it > 0 }?.let { json.put("fontId", it) }
        span.sizeLevel?.takeIf { it in 0..9 }?.let { json.put("sizeLevel", it) }
        span.bold?.let { json.put("bold", it) }
        span.italic?.let { json.put("italic", it) }
        span.underline?.let { json.put("underline", it) }
        span.color?.takeIf { colorRegex.matches(it) }?.let { json.put("color", it) }
        span.image?.takeIf { it.isNotBlank() }?.let { json.put("image", it) }
        return json
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? = if (has(key)) optBoolean(key, false) else null
}

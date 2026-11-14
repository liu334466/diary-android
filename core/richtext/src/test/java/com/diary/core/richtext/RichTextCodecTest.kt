package com.diary.core.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextCodecTest {
    @Test
    fun `编码后可无损解码`() {
        val document =
            RichTextDocument(
                blocks =
                    listOf(
                        RichTextBlock(
                            align = Align.CENTER,
                            list = ListType.UNORDERED,
                            text = "第一段文字",
                            spans =
                                listOf(
                                    RichTextSpan(0, 3, fontId = 3L, sizeLevel = 5),
                                    RichTextSpan(2, 5, bold = true, italic = false, underline = true),
                                    RichTextSpan(0, 5, color = "#111111"),
                                ),
                        ),
                        RichTextBlock(text = "第二段"),
                    ),
            )

        val encoded = RichTextCodec.encode(document)
        val decoded = RichTextCodec.decode(encoded)

        assertEquals(RichTextCodec.CURRENT_VERSION, decoded.version)
        assertNull(decoded.raw)
        assertEquals(document.blocks, decoded.blocks)
    }

    @Test
    fun `未知字段被忽略且不影响已知字段`() {
        val json =
            """
            {
              "v": 1,
              "extra": "future",
              "blocks": [
                {
                  "align": "right",
                  "list": "none",
                  "text": "hello",
                  "unknownBlockField": 1,
                  "spans": [
                    { "s": 0, "e": 3, "bold": true, "futureFlag": true }
                  ]
                }
              ]
            }
            """.trimIndent()

        val document = RichTextCodec.decode(json)

        assertEquals(1, document.blocks.size)
        val block = document.blocks.first()
        assertEquals(Align.RIGHT, block.align)
        assertEquals("hello", block.text)
        assertEquals(1, block.spans.size)
        assertEquals(true, block.spans.first().bold)
        assertNull(block.spans.first().italic)
    }

    @Test
    fun `高版本原文被保留且不会被覆盖`() {
        val futureJson = """{"v":99,"blocks":[],"newField":{"a":1}}"""

        val document = RichTextCodec.decode(futureJson)
        assertTrue(document.raw != null)
        assertEquals(futureJson, RichTextCodec.encode(document))
        assertEquals(futureJson, RichTextCodec.removeFontSpans(futureJson, 3L))
    }

    @Test
    fun `损坏 JSON 返回空正文且不抛出`() {
        val document = RichTextCodec.decode("{ not-json")
        assertEquals(1, document.blocks.size)
        assertEquals("", document.blocks.first().text)
        assertNull(document.raw)
    }

    @Test
    fun `损坏 JSON 的字体清理不改写原文`() {
        val broken = "{ not-json"
        assertEquals(broken, RichTextCodec.removeFontSpans(broken, 1L))
    }

    @Test
    fun `非法片段被丢弃或收敛`() {
        val json =
            """
            {
              "v": 1,
              "blocks": [
                {
                  "align": "diagonal",
                  "list": "weird",
                  "text": "abcdef",
                  "spans": [
                    { "s": -1, "e": 2, "bold": true },
                    { "s": 2, "e": 2 },
                    { "s": 3, "e": 100, "sizeLevel": 42, "color": "red" },
                    { "s": 0, "e": 2, "fontId": -1, "sizeLevel": 9 }
                  ]
                }
              ]
            }
            """.trimIndent()

        val block = RichTextCodec.decode(json).blocks.first()

        assertEquals(Align.LEFT, block.align)
        assertEquals(ListType.NONE, block.list)
        assertEquals(2, block.spans.size)
        assertEquals(3, block.spans[0].start)
        assertEquals(6, block.spans[0].end)
        assertNull(block.spans[0].sizeLevel)
        assertNull(block.spans[0].color)
        assertNull(block.spans[1].fontId)
        assertEquals(9, block.spans[1].sizeLevel)
    }

    @Test
    fun `移除字体片段只影响目标字体`() {
        val json =
            RichTextCodec.encode(
                RichTextDocument(
                    blocks =
                        listOf(
                            RichTextBlock(
                                text = "abcdef",
                                spans =
                                    listOf(
                                        RichTextSpan(0, 3, fontId = 7L),
                                        RichTextSpan(0, 3, bold = true),
                                        RichTextSpan(3, 6, fontId = 8L),
                                    ),
                            ),
                        ),
                ),
            )

        val cleaned = RichTextCodec.decode(RichTextCodec.removeFontSpans(json, 7L))
        val spans = cleaned.blocks.first().spans

        assertEquals(2, spans.size)
        assertEquals(true, spans[0].bold)
        assertEquals(8L, spans[1].fontId)
    }

    @Test
    fun `纯文本与字数统计`() {
        val document =
            RichTextDocument(
                blocks =
                    listOf(
                        RichTextBlock(text = "你好 世界"),
                        RichTextBlock(text = "第二行"),
                    ),
            )

        assertEquals("你好 世界\n第二行", RichTextCodec.plainText(document))
        assertEquals(7, RichTextCodec.charCount("你好 世界\n第二行"))
        assertFalse(RichTextCodec.charCount(" \n\t") > 0)
    }
}

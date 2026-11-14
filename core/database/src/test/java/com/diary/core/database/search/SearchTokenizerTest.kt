package com.diary.core.database.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文 FTS 分词规则，覆盖单字、双字、英文整词与混排、特殊字符。
 */
class SearchTokenizerTest {
    @Test
    fun `中文索引同时包含单字与相邻双字`() {
        val tokens = SearchTokenizer.indexOf("周末的雨").split(" ")

        assertTrue(tokens.containsAll(listOf("周", "末", "的", "雨")))
        assertTrue(tokens.containsAll(listOf("周末", "末的", "的雨")))
    }

    @Test
    fun `单字查询生成单 token`() {
        assertEquals("\"雨\"", SearchTokenizer.matchQuery("雨"))
    }

    @Test
    fun `双字查询按双字匹配`() {
        assertEquals("\"下雨\"", SearchTokenizer.matchQuery("下雨"))
    }

    @Test
    fun `长中文片段拆成多个双字并用 AND 连接`() {
        assertEquals("\"雨下\" AND \"下了\"", SearchTokenizer.matchQuery("雨下了"))
    }

    @Test
    fun `英文按整词小写索引不拆单字`() {
        val tokens = SearchTokenizer.indexOf("Hello World").split(" ")
        assertEquals(listOf("hello", "world"), tokens)
        assertEquals("\"hello\"", SearchTokenizer.matchQuery("HELLO"))
    }

    @Test
    fun `中英混排分别成段`() {
        val tokens = SearchTokenizer.indexOf("iPhone手机")
        assertTrue(tokens.contains("iphone"))
        assertTrue(tokens.contains("手机"))
        assertTrue(tokens.contains("手"))
    }

    @Test
    fun `多个关键词以空格分隔时按 AND 组合`() {
        assertEquals("\"雨\" AND \"书\"", SearchTokenizer.matchQuery("雨 书"))
    }

    @Test
    fun `空白与标点输入返回 null`() {
        assertNull(SearchTokenizer.matchQuery(""))
        assertNull(SearchTokenizer.matchQuery("   "))
        assertNull(SearchTokenizer.matchQuery("，。！？"))
    }

    @Test
    fun `特殊字符不会破坏 MATCH 表达式`() {
        val match = SearchTokenizer.matchQuery("雨\" OR *")
        assertEquals("\"雨\" AND \"or\"", match)
    }

    @Test
    fun `精确校验片段保留原始顺序且小写`() {
        assertEquals(listOf("周末", "rain"), SearchTokenizer.termsOf("周末 Rain"))
    }
}

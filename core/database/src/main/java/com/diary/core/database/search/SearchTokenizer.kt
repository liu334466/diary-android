package com.diary.core.database.search

import java.util.Locale

/**
 * 中文场景下的 FTS 分词与查询构造。
 *
 * SQLite 自带的 unicode61 分词器把连续汉字视为一个整词，直接入库会导致“雨”搜不到“周末的雨”。
 * 这里把中日韩文按「单字 + 相邻双字」写入索引，检索时同样拆成双字并用 AND 连接，
 * 再由调用方做一次原文包含校验，保证粗筛不误伤。
 *
 * 英文与数字按整词小写写入，不做单字拆分，避免索引无意义膨胀。
 */
object SearchTokenizer {
    /** 单个片段最大长度，超出部分截断，防止超长输入构造出巨型 MATCH 表达式。 */
    private const val MAX_SEGMENT_LENGTH = 64

    /** 单次查询最多拆出的 token 数。 */
    private const val MAX_QUERY_TOKENS = 24

    /**
     * 生成写入 FTS 的索引串，token 之间以空格分隔。
     */
    fun indexOf(vararg texts: String?): String =
        texts.filterNotNull()
            .flatMap(::tokensOf)
            .distinct()
            .joinToString(" ")

    /**
     * 供精确校验使用的原始片段，已做小写归一化；空输入返回空列表。
     */
    fun termsOf(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return splitSegments(raw)
            .map { it.text.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
    }

    /**
     * 构造 FTS MATCH 表达式。
     * 每个 token 用双引号包裹以规避特殊字符，中文片段拆成双字后用 AND 连接；无有效 token 时返回 null。
     */
    fun matchQuery(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val tokens = ArrayList<String>()
        loop@ for (segment in splitSegments(raw)) {
            val lowered = segment.text.lowercase(Locale.ROOT)
            if (segment.cjk) {
                if (lowered.length == 1) {
                    tokens += lowered
                } else {
                    for (index in 0 until lowered.length - 1) {
                        tokens += lowered.substring(index, index + 2)
                        if (tokens.size >= MAX_QUERY_TOKENS) break@loop
                    }
                }
            } else {
                tokens += lowered
            }
            if (tokens.size >= MAX_QUERY_TOKENS) break@loop
        }
        val cleaned =
            tokens
                .asSequence()
                .map { it.replace("\"", "").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_QUERY_TOKENS)
                .toList()
        if (cleaned.isEmpty()) return null
        return cleaned.joinToString(" AND ") { "\"$it\"" }
    }

    /**
     * 索引侧分词：中日韩文输出单字与双字，其他语言输出整词。
     */
    private fun tokensOf(text: String): List<String> {
        val result = ArrayList<String>()
        splitSegments(text).forEach { segment ->
            val lowered = segment.text.lowercase(Locale.ROOT)
            if (!segment.cjk) {
                result += lowered
                return@forEach
            }
            lowered.forEach { char -> result += char.toString() }
            for (index in 0 until lowered.length - 1) {
                result += lowered.substring(index, index + 2)
            }
        }
        return result
    }

    /**
     * 按「字母或数字」切分片段，标点、空白、符号均作为分隔符；
     * 连续汉字与连续西文会切成两段，避免混排文本把英文拆成单字。
     */
    private fun splitSegments(text: String): List<Segment> {
        val segments = ArrayList<Segment>()
        val buffer = StringBuilder()
        var bufferCjk: Boolean? = null

        fun flush() {
            if (buffer.isNotEmpty()) {
                segments += Segment(buffer.toString(), bufferCjk == true)
                buffer.setLength(0)
            }
            bufferCjk = null
        }

        text.forEach { char ->
            if (!isWordChar(char)) {
                flush()
                return@forEach
            }
            val cjk = isCjkChar(char)
            if (bufferCjk != null && bufferCjk != cjk) flush()
            bufferCjk = cjk
            if (buffer.length < MAX_SEGMENT_LENGTH) buffer.append(char)
        }
        flush()
        return segments
    }

    private fun isWordChar(char: Char): Boolean = Character.isLetterOrDigit(char)

    private fun isCjkChar(char: Char): Boolean =
        when (Character.UnicodeScript.of(char.code)) {
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            -> true

            else -> false
        }

    private data class Segment(val text: String, val cjk: Boolean)
}

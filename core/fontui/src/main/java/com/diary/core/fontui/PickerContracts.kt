package com.diary.core.fontui

import android.graphics.Typeface
import com.diary.core.model.FontAsset

/**
 * 字体与字号的三级生效范围：选区（L3）、整篇（L2）、全局（L1）。
 */
enum class FontScope {
    SELECTION,
    WHOLE,
    GLOBAL,
    ;

    companion object {
        fun from(raw: String?): FontScope? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 字体选择结果，宿主根据 scope 决定写入位置。
 */
data class FontPickResult(
    val fontId: Long?,
    val scope: FontScope,
) {
    companion object {
        const val RESULT_KEY = "font_pick_result"
        const val ARG_SCOPE = "scope"
        const val ARG_FONT_ID = "font_id"

        fun read(bundle: android.os.Bundle): FontPickResult =
            FontPickResult(
                fontId = bundle.getLong(ARG_FONT_ID, 0L).takeIf { it > 0L },
                scope = FontScope.from(bundle.getString(ARG_SCOPE)) ?: FontScope.WHOLE,
            )
    }
}

/**
 * 字号选择结果。
 */
data class SizePickResult(
    val level: Int,
    val scope: FontScope,
) {
    companion object {
        const val RESULT_KEY = "size_pick_result"
        const val ARG_SCOPE = "scope"
        const val ARG_LEVEL = "level"

        fun read(bundle: android.os.Bundle): SizePickResult =
            SizePickResult(
                level = bundle.getInt(ARG_LEVEL, com.diary.core.model.DiaryLimits.DEFAULT_FONT_SIZE_LEVEL),
                scope = FontScope.from(bundle.getString(ARG_SCOPE)) ?: FontScope.WHOLE,
            )
    }
}

/**
 * 选择器内部的字体条目。id 为 null 表示系统默认字体。
 */
internal data class FontOption(
    val id: Long?,
    val name: String,
    val typeface: Typeface,
    val invalid: Boolean,
    val sourceLabel: String,
)

internal fun FontAsset.isUsable(): Boolean = status == FontAsset.STATUS_OK

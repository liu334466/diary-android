package com.diary.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 界面日期与文件大小格式化，保证列表、编辑页、回收站展示口径一致。
 */
object DiaryFormats {
    /** 列表与正文元信息日期：2026-11-13。 */
    fun date(epochMillis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMillis))

    /** 回收站删除时间：2026.11.13 21:04。 */
    fun dateTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

    /** 保存状态时间：21:04。 */
    fun time(epochMillis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

    /** 字体文件大小：8.6 MB / 820 KB。 */
    fun fileSize(bytes: Long): String {
        if (bytes <= 0L) return "未知大小"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        return String.format(Locale.getDefault(), "%.1f MB", kb / 1024.0)
    }

    /** 字数展示：12,000。 */
    fun charCount(count: Int): String = String.format(Locale.getDefault(), "%,d", count)
}

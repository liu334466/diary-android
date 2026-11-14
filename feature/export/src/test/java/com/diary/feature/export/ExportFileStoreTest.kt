package com.diary.feature.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 导出文件命名是纯逻辑，覆盖非法字符、空标题与超长标题三种异常输入。
 */
class ExportFileStoreTest {
    private val fixedTime: Long =
        Calendar.getInstance(TimeZone.getDefault(), Locale.getDefault())
            .apply {
                set(2026, Calendar.NOVEMBER, 13, 9, 5, 0)
                set(Calendar.MILLISECOND, 0)
            }
            .timeInMillis

    @Test
    fun `文件名包含标题与时间戳`() {
        val name = ExportFileStore.fileNameBase("周末的雨", fixedTime)
        assertEquals("周末的雨-20261113-0905", name)
    }

    @Test
    fun `非法字符替换为下划线`() {
        val name = ExportFileStore.fileNameBase("a/b:c*d?e\"f<g>h|i j", fixedTime)
        assertFalse(name.contains('/'))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('*'))
        assertTrue(name.endsWith("-20261113-0905"))
    }

    @Test
    fun `空标题回退未命名日记`() {
        assertEquals("未命名日记-20261113-0905", ExportFileStore.fileNameBase("   ", fixedTime))
        assertEquals("未命名日记-20261113-0905", ExportFileStore.fileNameBase(null, fixedTime))
    }

    @Test
    fun `超长标题截断到二十个字符`() {
        val name = ExportFileStore.fileNameBase("一二三四五六七八九十一二三四五六七八九十多余", fixedTime)
        assertEquals("一二三四五六七八九十一二三四五六七八九十-20261113-0905", name)
    }
}

package com.diary.core.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 外观偏好：默认跟随系统、手动模式优先，且选择能跨进程重启保留。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemeModeStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `未设置时默认跟随系统且按系统夜间模式解析`() {
        val store = ThemeModeStore(context)
        assertEquals(DiaryThemeMode.SYSTEM, store.mode)
        assertEquals(DiaryThemeMode.LIGHT, store.resolved())
    }

    @Test
    @Config(sdk = [28], qualifiers = "night")
    fun `跟随系统时夜间模式解析为深色`() {
        assertEquals(DiaryThemeMode.DARK, ThemeModeStore(context).resolved())
    }

    @Test
    fun `手动深色与浅色覆盖系统设置`() {
        val store = ThemeModeStore(context)
        store.mode = DiaryThemeMode.DARK
        assertEquals(DiaryThemeMode.DARK, store.resolved())
        assertEquals(DiaryThemeMode.DARK, ThemeModeStore(context).mode)

        store.mode = DiaryThemeMode.LIGHT
        assertEquals(DiaryThemeMode.LIGHT, store.resolved())
        assertEquals(DiaryThemeMode.LIGHT, ThemeModeStore(context).mode)
    }

    @Test
    @Config(sdk = [28], qualifiers = "night")
    fun `暖纸为显式选择不受系统夜间模式影响`() {
        val store = ThemeModeStore(context)
        store.mode = DiaryThemeMode.PAPER
        assertEquals(DiaryThemeMode.PAPER, store.resolved())
    }
}

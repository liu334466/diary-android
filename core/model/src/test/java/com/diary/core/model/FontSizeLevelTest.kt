package com.diary.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontSizeLevelTest {
    @Test
    fun `共 10 档且索引连续`() {
        assertEquals(10, FontSizeLevel.entries.size)
        assertEquals((0..9).toList(), FontSizeLevel.entries.map { it.level })
    }

    @Test
    fun `默认字号为四号索引 5`() {
        assertEquals(5, DiaryLimits.DEFAULT_FONT_SIZE_LEVEL)
        assertEquals(FontSizeLevel.SI_HAO, FontSizeLevel.of(DiaryLimits.DEFAULT_FONT_SIZE_LEVEL))
        assertEquals(FontSizeLevel.Default, FontSizeLevel.of(5))
    }

    @Test
    fun `非法档位返回空并回退默认字号`() {
        assertNull(FontSizeLevel.of(-1))
        assertNull(FontSizeLevel.of(10))
        assertNull(FontSizeLevel.of(null))
        assertTrue(FontSizeLevel.isValid(0))
        assertFalse(FontSizeLevel.isValid(10))
        assertEquals(14f, FontSizeLevel.sizeSpOf(99), 0.01f)
    }
}

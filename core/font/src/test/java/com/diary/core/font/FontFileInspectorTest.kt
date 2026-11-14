package com.diary.core.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FontFileInspectorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `TrueType 文件头通过校验`() {
        val file = tempFolder.newFile("valid.ttf")
        file.writeBytes(ttfHeader())

        assertTrue(FontFileInspector.isSupportedFontFile(file))
    }

    @Test
    fun `OTF 文件头通过校验`() {
        val file = tempFolder.newFile("valid.otf")
        val header = ttfHeader()
        "OTTO".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        file.writeBytes(header)

        assertTrue(FontFileInspector.isSupportedFontFile(file))
    }

    @Test
    fun `伪造或损坏文件被拒绝`() {
        val garbage = tempFolder.newFile("broken.ttf")
        garbage.writeText("this is not a font file")

        val short = tempFolder.newFile("short.ttf")
        short.writeBytes(byteArrayOf(0x00, 0x01))

        val emptyTables = tempFolder.newFile("empty_tables.ttf")
        emptyTables.writeBytes(ttfHeader(numTables = 0))

        assertFalse(FontFileInspector.isSupportedFontFile(garbage))
        assertFalse(FontFileInspector.isSupportedFontFile(short))
        assertFalse(FontFileInspector.isSupportedFontFile(emptyTables))
        assertFalse(FontFileInspector.isSupportedFontFile(File(tempFolder.root, "missing.ttf")))
    }

    @Test
    fun `扩展名仅接受 TTF 与 OTF`() {
        assertTrue(FontFileInspector.hasSupportedExtension("abc.ttf"))
        assertTrue(FontFileInspector.hasSupportedExtension("ABC.OTF"))
        assertFalse(FontFileInspector.hasSupportedExtension("abc.ttc"))
        assertFalse(FontFileInspector.hasSupportedExtension("abc"))
    }

    /**
     * 用开发机上的真实中文字体验证结构解析；其他环境无字体样本时自动跳过。
     */
    @Test
    fun `真实中文字体文件通过结构校验`() {
        val candidates =
            listOf(
                File("C:/Windows/Fonts/simhei.ttf"),
                File("C:/Windows/Fonts/Deng.ttf"),
                File("C:/Windows/Fonts/NotoSansSC-VF.ttf"),
                File("C:/Windows/Fonts/Noto Sans SC (TrueType).otf"),
            ).filter { it.isFile }

        org.junit.Assume.assumeTrue("本机无真实字体样本，跳过", candidates.isNotEmpty())

        candidates.forEach { file ->
            assertTrue("字体结构校验失败：${file.name}", FontFileInspector.isSupportedFontFile(file))
            assertTrue("扩展名校验失败：${file.name}", FontFileInspector.hasSupportedExtension(file.name))
        }
    }

    private fun ttfHeader(numTables: Int = 12): ByteArray =
        byteArrayOf(
            0x00, 0x01, 0x00, 0x00,
            (numTables shr 8).toByte(), numTables.toByte(),
            0x00, 0x80.toByte(), 0x00, 0x03, 0x00, 0x08,
        )
}

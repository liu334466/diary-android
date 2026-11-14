package com.diary.core.font

import com.diary.core.model.DiaryLimits
import java.io.DataInputStream
import java.io.File

/**
 * 字体文件结构校验。仅做文件头与表数量检查，不依赖 Android 运行时，
 * 便于在单元测试中验证导入校验逻辑。
 */
object FontFileInspector {
    /** TrueType 轮廓、CFF 轮廓、旧版 Apple 字体、Type1 字体。 */
    private val supportedMagic =
        listOf(
            byteArrayOf(0x00, 0x01, 0x00, 0x00),
            "OTTO".toByteArray(Charsets.US_ASCII),
            "true".toByteArray(Charsets.US_ASCII),
            "typ1".toByteArray(Charsets.US_ASCII),
        )

    fun isSupportedFontFile(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_SIZE) return false
        val header = ByteArray(HEADER_SIZE)
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                input.readFully(header)
            }
        } catch (_: Exception) {
            return false
        }
        if (supportedMagic.none { it.contentEquals(header.copyOfRange(0, 4)) }) return false
        val numTables = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        return numTables in 1..MAX_TABLES
    }

    fun hasSupportedExtension(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in DiaryLimits.SUPPORTED_FONT_EXTENSIONS

    private const val HEADER_SIZE = 12
    private const val MAX_TABLES = 512
}

package com.diary.core.font

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.diary.core.database.repository.FontRepository
import com.diary.core.model.DiaryLimits
import com.diary.core.model.FontAsset
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体导入：系统文件选择器 URI → 校验 → 拷贝私有目录 → 登记数据库。
 * 任一步失败都会清理临时文件与半成品记录，允许重新导入。
 */
@Singleton
class FontImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fontRepository: FontRepository,
        private val fileStore: FontFileStore,
    ) {
        suspend fun import(uri: Uri): FontImportResult =
            withContext(Dispatchers.IO) {
                val metadata =
                    queryMetadata(uri) ?: return@withContext FontImportResult.Failure(
                        FontImportResult.Reason.UNREADABLE,
                        "无法读取所选文件",
                    )
                val displayName = metadata.first
                val declaredSize = metadata.second

                if (!FontFileInspector.hasSupportedExtension(displayName)) {
                    return@withContext FontImportResult.Failure(
                        FontImportResult.Reason.UNSUPPORTED_FORMAT,
                        "仅支持 TTF / OTF 字体文件",
                    )
                }
                if (declaredSize > DiaryLimits.MAX_FONT_SIZE_BYTES) {
                    return@withContext FontImportResult.Failure(
                        FontImportResult.Reason.FILE_TOO_LARGE,
                        "字体文件超过 30MB，无法导入",
                    )
                }
                if (fontRepository.countImported() >= DiaryLimits.MAX_FONTS) {
                    return@withContext FontImportResult.Failure(
                        FontImportResult.Reason.LIMIT_REACHED,
                        "最多导入 10 个字体，请先删除一个自定义字体",
                    )
                }

                var tempFile: File? = null
                var finalFile: File? = null
                try {
                    tempFile = fileStore.createTempFile()
                    val copied = copyToFile(uri, tempFile)
                    when {
                        copied <= 0L ->
                            FontImportResult.Failure(
                                FontImportResult.Reason.EMPTY_FILE,
                                "字体文件为空",
                            )
                        copied > DiaryLimits.MAX_FONT_SIZE_BYTES ->
                            FontImportResult.Failure(
                                FontImportResult.Reason.FILE_TOO_LARGE,
                                "字体文件超过 30MB，无法导入",
                            )
                        !FontFileInspector.isSupportedFontFile(tempFile) ->
                            FontImportResult.Failure(
                                FontImportResult.Reason.INVALID_FONT,
                                "字体文件已损坏或无法解析",
                            )
                        else -> {
                            val finalName = buildFileName(displayName)
                            val fontDisplayName =
                                displayName.substringBeforeLast('.').take(20)
                                    .ifBlank { "自定义字体" }
                            val importedAt = System.currentTimeMillis()
                            finalFile = fileStore.fileOf(finalName)
                            if (!tempFile.renameTo(finalFile)) {
                                return@withContext FontImportResult.Failure(
                                    FontImportResult.Reason.IO_ERROR,
                                    "字体文件保存失败，请重试",
                                )
                            }
                            val id =
                                fontRepository.registerImported(
                                    displayName = fontDisplayName,
                                    fileName = finalName,
                                    fileSize = copied,
                                    now = importedAt,
                                )
                            finalFile = null // 已入库，交由字体服务管理生命周期
                            return@withContext FontImportResult.Success(
                                FontAsset(
                                    id = id,
                                    displayName = fontDisplayName,
                                    fileName = finalName,
                                    fileSize = copied,
                                    source = FontAsset.SOURCE_IMPORTED,
                                    status = FontAsset.STATUS_OK,
                                    importedAt = importedAt,
                                ),
                            )
                        }
                    }
                } catch (_: Exception) {
                    FontImportResult.Failure(FontImportResult.Reason.IO_ERROR, "字体导入失败，请重试")
                } finally {
                    tempFile?.delete()
                    finalFile?.delete()
                    releaseUriPermission(uri)
                }
            }

        private fun queryMetadata(uri: Uri): Pair<String, Long>? {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                    if (!name.isNullOrBlank()) return name to size
                }
            }
            val fallback = uri.lastPathSegment ?: return null
            return fallback to -1L
        }

        private fun copyToFile(
            uri: Uri,
            target: File,
        ): Long {
            val input = context.contentResolver.openInputStream(uri) ?: return 0L
            input.use { source ->
                target.outputStream().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > DiaryLimits.MAX_FONT_SIZE_BYTES) return total
                        sink.write(buffer, 0, read)
                    }
                    sink.flush()
                    return total
                }
            }
        }

        private fun buildFileName(displayName: String): String {
            val extension = displayName.substringAfterLast('.', "ttf").lowercase()
            return "font_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
        }

        private fun releaseUriPermission(uri: Uri) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }

        private companion object {
            const val BUFFER_SIZE = 64 * 1024
        }
    }

/**
 * 导入结果。文案与 PRD 3.4 异常处理表一致，可直接展示。
 */
sealed interface FontImportResult {
    data class Success(val font: FontAsset) : FontImportResult

    data class Failure(val reason: Reason, val message: String) : FontImportResult

    enum class Reason {
        UNSUPPORTED_FORMAT,
        EMPTY_FILE,
        FILE_TOO_LARGE,
        LIMIT_REACHED,
        UNREADABLE,
        INVALID_FONT,
        IO_ERROR,
    }
}

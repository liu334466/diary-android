package com.diary.core.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 日记插图存储：图片拷贝到应用私有目录，正文只保存文件名，卸载应用即随数据一并清除。
 */
@Singleton
class DiaryImageStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val directory: File
            get() = File(context.filesDir, DIRECTORY_NAME).apply { if (!exists()) mkdirs() }

        private val cache = LruCache<String, Bitmap>(MAX_CACHED_BITMAPS)

        fun fileOf(fileName: String): File = File(directory, fileName)

        fun exists(fileName: String): Boolean = fileOf(fileName).isFile

        /**
         * 从系统选择器 URI 拷贝图片，返回生成的文件名；失败返回 null。
         */
        fun save(uri: Uri): String? {
            val extension = resolveExtension(uri)
            val target = File(directory, "img_${System.currentTimeMillis()}_${shortId()}.$extension")
            val temp = File(directory, "temp_${shortId()}.tmp")
            return try {
                val input = context.contentResolver.openInputStream(uri) ?: return null
                input.use { source ->
                    temp.outputStream().use { sink ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_IMAGE_BYTES) return null
                            sink.write(buffer, 0, read)
                        }
                        sink.flush()
                    }
                }
                if (decodeBounds(temp) == null) return null
                if (!temp.renameTo(target)) return null
                target.name
            } catch (_: Exception) {
                null
            } finally {
                temp.delete()
            }
        }

        /**
         * 按最大宽度解码，长图等比缩放，避免大图撑爆内存。
         */
        fun load(
            fileName: String,
            maxWidthPx: Int,
        ): Bitmap? {
            cache.get(fileName)?.let { return it }
            val file = fileOf(fileName)
            if (!file.isFile) return null
            val bounds = decodeBounds(file) ?: return null
            var sample = 1
            while (bounds.first / (sample * 2) >= maxWidthPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded =
                try {
                    BitmapFactory.decodeFile(file.absolutePath, options)
                } catch (_: Exception) {
                    null
                } ?: return null
            val scaled =
                if (decoded.width > maxWidthPx && maxWidthPx > 0) {
                    val height = max(1, (decoded.height.toFloat() / decoded.width * maxWidthPx).toInt())
                    Bitmap.createScaledBitmap(decoded, maxWidthPx, height, true).also {
                        if (it !== decoded) decoded.recycle()
                    }
                } else {
                    decoded
                }
            // 与设备密度对齐，避免 BitmapDrawable 按 160dpi 基准二次放大。
            scaled.density = context.resources.displayMetrics.densityDpi
            cache.put(fileName, scaled)
            return scaled
        }

        fun delete(fileName: String) {
            cache.remove(fileName)
            fileOf(fileName).delete()
        }

        /** 备份用：列出私有目录中的全部插图文件。 */
        fun listFiles(): List<File> = directory.listFiles()?.filter { it.isFile }.orEmpty()

        /** 恢复备份后清空解码缓存，避免按同名文件命中旧位图。 */
        fun invalidateCache() {
            cache.evictAll()
        }

        private fun decodeBounds(file: File): Pair<Int, Int>? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            return try {
                BitmapFactory.decodeFile(file.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    null
                } else {
                    options.outWidth to options.outHeight
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun resolveExtension(uri: Uri): String {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            return when (mime) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
        }

        private fun shortId(): String = UUID.randomUUID().toString().take(8)

        private companion object {
            const val DIRECTORY_NAME = "diary_images"
            const val BUFFER_SIZE = 64 * 1024
            const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
            const val MAX_CACHED_BITMAPS = 6
        }
    }

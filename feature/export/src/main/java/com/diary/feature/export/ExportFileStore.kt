package com.diary.feature.export

import android.content.Context
import com.diary.core.model.DiaryLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导出文件统一落在 cacheDir/diary_exports，避免占用用户可见目录；
 * 每次导出前清空旧文件，保证不遗留残缺文件。
 */
@Singleton
class ExportFileStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun directory(): File =
            File(context.cacheDir, DIR_NAME).apply {
                if (!isDirectory) mkdirs()
            }

        fun clear() {
            directory().listFiles()?.forEach { it.delete() }
        }

        fun baseName(
            title: String?,
            now: Long = System.currentTimeMillis(),
        ): String = fileNameBase(title, now)

        companion object {
            const val DIR_NAME = "diary_exports"

            private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\s]+")

            /**
             * 文件名：标题-年月日-时分。标题为空时回退“diary”，并限制长度避免超长路径。
             */
            fun fileNameBase(
                title: String?,
                now: Long,
            ): String {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
                val safe =
                    (title ?: DiaryLimits.UNTITLED_DIARY_NAME)
                        .replace(ILLEGAL, "_")
                        .trim('_')
                        .take(MAX_TITLE_IN_NAME)
                return if (safe.isEmpty()) {
                    "${DiaryLimits.UNTITLED_DIARY_NAME}-$stamp"
                } else {
                    "$safe-$stamp"
                }
            }

            private const val MAX_TITLE_IN_NAME = 20
        }
    }

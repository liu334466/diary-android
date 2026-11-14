package com.diary.core.font

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字体文件在应用私有目录中的存放位置。
 */
@Singleton
class FontFileStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val directory: File = File(context.filesDir, DIRECTORY_NAME)

        fun fileOf(fileName: String): File = File(directory, fileName)

        fun exists(fileName: String): Boolean = fileOf(fileName).isFile

        fun prepareDirectory(): File {
            if (!directory.exists()) directory.mkdirs()
            return directory
        }

        fun createTempFile(): File {
            prepareDirectory()
            return File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, directory)
        }

        fun delete(fileName: String): Boolean {
            val file = fileOf(fileName)
            return !file.exists() || file.delete()
        }

        companion object {
            const val DIRECTORY_NAME = "fonts"
            const val TEMP_PREFIX = "import_"
            const val TEMP_SUFFIX = ".tmp"
        }
    }

package com.diary.core.backup

/**
 * 备份结果摘要，用于导出/恢复完成后的提示文案。
 */
data class BackupSummary(
    val createdAt: Long,
    val diaryCount: Int,
    val categoryCount: Int,
    val tagCount: Int,
    val imageCount: Int,
    val fontCount: Int,
)

/**
 * 备份文件不合法时抛出，message 直接面向用户展示。
 */
class BackupException(message: String) : IllegalArgumentException(message)

/**
 * 备份包格式约定：ZIP 内包含 backup.json 清单，以及 images/、fonts/ 两个文件目录。
 * 版本号与清单结构同时变更时递增 [VERSION]，旧版本备份会被拒绝恢复。
 */
internal object BackupFormat {
    const val FORMAT = "diary-backup"
    const val VERSION = 1

    const val ENTRY_JSON = "backup.json"
    const val IMAGE_PREFIX = "images/"
    const val FONT_PREFIX = "fonts/"

    /** 单个备份包最多包含的文件条目数。 */
    const val MAX_ENTRIES = 5_000

    /** 清单本身的上限，防止异常文件撑爆内存。 */
    const val MAX_JSON_BYTES = 64L * 1024 * 1024

    /** 单个插图或字体文件的上限。 */
    const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** 解压后的总字节上限。 */
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024

    const val BUFFER_SIZE = 64 * 1024
}

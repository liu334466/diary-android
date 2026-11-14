package com.diary.core.model

/**
 * 产品约束常量，集中维护，避免各模块散落魔法数字。
 * 数值来源：PRD 3.3 / 3.4 / 3.5 与 4.2–4.7。
 */
object DiaryLimits {
    /** “未分类”固定主键，系统分类不可删除、不可重命名。 */
    const val UNCATEGORIZED_ID: Long = 1L

    /** 未分类展示名称。 */
    const val UNCATEGORIZED_NAME: String = "未分类"

    /** 自定义分类夹上限。 */
    const val MAX_CATEGORIES: Int = 20

    /** 自定义字体上限。 */
    const val MAX_FONTS: Int = 10

    /** 单个字体文件上限 30MB。 */
    const val MAX_FONT_SIZE_BYTES: Long = 30L * 1024 * 1024

    /** 回收站保留天数。 */
    const val TRASH_RETENTION_DAYS: Int = 30

    /** 回收站保留毫秒数。 */
    const val TRASH_RETENTION_MILLIS: Long = TRASH_RETENTION_DAYS * 24L * 60L * 60L * 1000L

    /** 字号档位数量，索引 0–9。 */
    const val FONT_SIZE_LEVEL_COUNT: Int = 10

    /** 全局默认字号：四号，索引 5。 */
    const val DEFAULT_FONT_SIZE_LEVEL: Int = 5

    /** 标题长度上限。 */
    const val MAX_TITLE_LENGTH: Int = 50

    /** 单篇正文字数建议上限。 */
    const val MAX_CONTENT_CHARS: Int = 20_000

    /** 单篇插图数量上限。 */
    const val MAX_IMAGES: Int = 20

    /** 标签库总量上限。 */
    const val MAX_TAGS: Int = 50

    /** 单篇日记可挂载的标签数量上限。 */
    const val MAX_TAGS_PER_DIARY: Int = 10

    /** 标签名称长度上限。 */
    const val MAX_TAG_NAME_LENGTH: Int = 10

    /** 标题为空时的列表文案。 */
    const val UNTITLED_DIARY_NAME: String = "未命名日记"

    /** 支持的字体扩展名（小写）。 */
    val SUPPORTED_FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf")
}

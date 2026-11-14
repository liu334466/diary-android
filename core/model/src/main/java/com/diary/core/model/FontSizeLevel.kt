package com.diary.core.model

/**
 * 正文 10 档字号，索引与 PRD 4.5 的 sizeLevel 一致。
 *
 * @property level 0–9 的档位索引
 * @property label 选择器展示名称
 * @property sizeSp 渲染字号
 */
enum class FontSizeLevel(
    val level: Int,
    val label: String,
    val sizeSp: Float,
) {
    CHU_HAO(0, "初号", 42f),
    YI_HAO(1, "一号", 26f),
    ER_HAO(2, "二号", 22f),
    SAN_HAO(3, "三号", 16f),
    XIAO_SAN(4, "小三", 15f),
    SI_HAO(5, "四号", 14f),
    XIAO_SI(6, "小四", 12f),
    WU_HAO(7, "五号", 10.5f),
    XIAO_WU(8, "小五", 9f),
    LIU_HAO(9, "六号", 7.5f),
    ;

    companion object {
        val Default: FontSizeLevel = SI_HAO

        private val byLevel = entries.associateBy { it.level }

        /** 越界或空值返回 null，调用方据此走继承或回退。 */
        fun of(level: Int?): FontSizeLevel? = level?.let { byLevel[it] }

        fun isValid(level: Int?): Boolean = level != null && byLevel.containsKey(level)

        fun sizeSpOf(level: Int?): Float = of(level)?.sizeSp ?: Default.sizeSp
    }
}

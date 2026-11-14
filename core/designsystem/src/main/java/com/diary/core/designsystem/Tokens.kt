package com.diary.core.designsystem

import android.content.Context
import android.util.TypedValue

/**
 * 当前主题下的语义色板。所有颜色都从主题属性解析，切换主题后需重新获取。
 */
class DiaryPalette(
    val bg: Int,
    val bgSoft: Int,
    val ink: Int,
    val ink2: Int,
    val muted: Int,
    val line: Int,
    val lineStrong: Int,
    val danger: Int,
    val dangerSoft: Int,
    val highlight: Int,
    val pressed: Int,
)

/**
 * 简约风设计 Token，全局唯一来源。新增颜色或间距必须先加在这里。
 * 颜色不再是固定常量，而是按当前主题属性解析，支持浅色 / 深色 / 暖纸多套配色。
 */
object DiaryColor {
    fun of(context: Context): DiaryPalette {
        val theme = context.theme

        fun resolve(attr: Int): Int {
            val value = TypedValue()
            theme.resolveAttribute(attr, value, true)
            return value.data
        }
        return DiaryPalette(
            bg = resolve(R.attr.dsColorBg),
            bgSoft = resolve(R.attr.dsColorBgSoft),
            ink = resolve(R.attr.dsColorInk),
            ink2 = resolve(R.attr.dsColorInk2),
            muted = resolve(R.attr.dsColorMuted),
            line = resolve(R.attr.dsColorLine),
            lineStrong = resolve(R.attr.dsColorLineStrong),
            danger = resolve(R.attr.dsColorDanger),
            dangerSoft = resolve(R.attr.dsColorDangerSoft),
            highlight = resolve(R.attr.dsColorHighlight),
            pressed = resolve(R.attr.dsColorPressed),
        )
    }
}

/**
 * 间距阶梯，单位 dp。
 */
object DiarySpacing {
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24
    const val XXL = 32
}

/**
 * 圆角，单位 dp。
 */
object DiaryRadius {
    const val CONTROL = 8
    const val SHEET = 12
}

/**
 * 界面字号，单位 sp。日记正文字号由用户档位决定，不在此处定义。
 */
object DiaryType {
    const val TITLE = 20
    const val BODY = 16
    const val SUB = 14
    const val CAPTION = 12
}

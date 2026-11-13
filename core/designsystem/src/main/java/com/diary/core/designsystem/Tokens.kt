package com.diary.core.designsystem

/**
 * 简约风设计 Token，全局唯一来源。新增颜色或间距必须先加在这里。
 */
object DiaryColor {
    val Bg: Int = 0xFFFFFFFF.toInt()
    val BgSoft: Int = 0xFFF7F7F6.toInt()
    val Ink: Int = 0xFF111111.toInt()
    val Ink2: Int = 0xFF444444.toInt()
    val Muted: Int = 0xFF8A8A8A.toInt()
    val Line: Int = 0xFFE6E6E6.toInt()
    val Danger: Int = 0xFFB3261E.toInt()
}

/**
 * 间距阶梯，单位 dp。
 */
object DiarySpacing {
    const val Xs = 4
    const val Sm = 8
    const val Md = 12
    const val Lg = 16
    const val Xl = 24
    const val Xxl = 32
}

/**
 * 圆角，单位 dp。
 */
object DiaryRadius {
    const val Control = 8
    const val Sheet = 12
}

/**
 * 界面字号，单位 sp。日记正文字号由用户档位决定，不在此处定义。
 */
object DiaryType {
    const val Title = 20
    const val Body = 16
    const val Sub = 14
    const val Caption = 12
}

package com.diary.core.common

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外观模式：跟随系统、浅色、深色、暖纸。
 */
enum class DiaryThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    PAPER("暖纸"),
}

/**
 * 外观偏好的同步读写。
 *
 * 主题必须在 `setContentView` 之前确定，DataStore 是异步的因此这里改用 SharedPreferences，
 * 保证冷启动时能同步读到上次选择，不会先闪一下浅色再切深色。
 */
@Singleton
class ThemeModeStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val preferences =
            context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

        var mode: DiaryThemeMode
            get() {
                val raw = preferences.getString(KEY_MODE, null) ?: return DiaryThemeMode.SYSTEM
                return DiaryThemeMode.entries.firstOrNull { it.name == raw } ?: DiaryThemeMode.SYSTEM
            }
            set(value) {
                preferences.edit().putString(KEY_MODE, value.name).apply()
            }

        /**
         * 把「跟随系统」解析成具体模式：系统夜间模式开启时落到深色，否则浅色。
         * 暖纸是显式选择，不参与系统联动。
         */
        fun resolved(): DiaryThemeMode =
            when (mode) {
                DiaryThemeMode.SYSTEM ->
                    if (isSystemNight()) {
                        DiaryThemeMode.DARK
                    } else {
                        DiaryThemeMode.LIGHT
                    }

                else -> mode
            }

        private fun isSystemNight(): Boolean {
            val uiMode = context.resources.configuration.uiMode
            return uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }

        private companion object {
            const val FILE_NAME = "diary_theme"
            const val KEY_MODE = "theme_mode"
        }
    }

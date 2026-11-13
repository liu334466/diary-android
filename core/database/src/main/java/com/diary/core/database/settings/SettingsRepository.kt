package com.diary.core.database.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "diary_settings")

/**
 * 全局设置快照，对应 PRD 4.6。
 */
data class UserSettings(
    val defaultFontId: Long? = null,
    val defaultFontSizeLevel: Int = DEFAULT_FONT_SIZE_LEVEL,
    val lastCategoryId: Long = UNCATEGORIZED_ID
) {
    companion object {
        const val UNCATEGORIZED_ID = 1L

        /** 精简 10 档中默认四号，索引 6。 */
        const val DEFAULT_FONT_SIZE_LEVEL = 6
    }
}

/**
 * 全局设置的读写入口，基于 DataStore。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        UserSettings(
            defaultFontId = prefs[Keys.DefaultFontId],
            defaultFontSizeLevel = prefs[Keys.DefaultFontSizeLevel]
                ?: UserSettings.DEFAULT_FONT_SIZE_LEVEL,
            lastCategoryId = prefs[Keys.LastCategoryId] ?: UserSettings.UNCATEGORIZED_ID
        )
    }

    suspend fun setDefaultFontId(fontId: Long?) {
        context.settingsDataStore.edit { prefs ->
            if (fontId == null) {
                prefs.remove(Keys.DefaultFontId)
            } else {
                prefs[Keys.DefaultFontId] = fontId
            }
        }
    }

    suspend fun setDefaultFontSizeLevel(level: Int) {
        require(level in 0..9) { "字号档位必须在 0..9 之间" }
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DefaultFontSizeLevel] = level
        }
    }

    suspend fun setLastCategoryId(categoryId: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LastCategoryId] = categoryId
        }
    }

    private object Keys {
        val DefaultFontId = longPreferencesKey("default_font_id")
        val DefaultFontSizeLevel = intPreferencesKey("default_font_size_level")
        val LastCategoryId = longPreferencesKey("last_category_id")
    }
}

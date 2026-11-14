package com.diary.core.database.di

import android.content.Context
import com.diary.core.database.DiaryDatabase
import com.diary.core.database.dao.CategoryDao
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.DiaryFtsDao
import com.diary.core.database.dao.FontAssetDao
import com.diary.core.database.dao.TagDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库单例与 DAO 的 Hilt 绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): DiaryDatabase = DiaryDatabase.build(context)

    @Provides
    fun provideDiaryDao(database: DiaryDatabase): DiaryDao = database.diaryDao()

    @Provides
    fun provideCategoryDao(database: DiaryDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideFontAssetDao(database: DiaryDatabase): FontAssetDao = database.fontAssetDao()

    @Provides
    fun provideDiaryFtsDao(database: DiaryDatabase): DiaryFtsDao = database.diaryFtsDao()

    @Provides
    fun provideTagDao(database: DiaryDatabase): TagDao = database.tagDao()
}

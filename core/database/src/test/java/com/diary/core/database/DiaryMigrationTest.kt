package com.diary.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diary.core.database.search.SearchTokenizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 真实文件迁移：确认历史日记不丢，且新索引表可用。
 * v1 的建表语句与 identityHash 取自 `schemas/.../1.json`，避免手写漂移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiaryMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `v1 升级到最新版保留日记并建好索引与标签表`() =
        runTest {
            createVersion1Database()

            val migrated =
                Room.databaseBuilder(context, DiaryDatabase::class.java, DB_NAME)
                    .addMigrations(*DiaryMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()

            assertEquals(1, migrated.diaryDao().countAll())
            assertEquals("升级前的日记", migrated.diaryDao().getById(1L)?.title)
            // 迁移内只建表，索引内容由 SearchIndexManager 在启动后补建。
            assertEquals(0, migrated.diaryFtsDao().count())
            migrated.diaryFtsDao().upsert(
                com.diary.core.database.entity.DiaryFtsEntity(
                    rowId = 1L,
                    titleTokens = SearchTokenizer.indexOf("升级前的日记"),
                    bodyTokens = SearchTokenizer.indexOf("历史正文"),
                ),
            )
            assertEquals(1, migrated.diaryFtsDao().search("\"升级\"", 10).size)
            // v3 标签表可用，且能绑定到升级前的老日记。
            val tagId =
                migrated.tagDao().insert(
                    com.diary.core.database.entity.TagEntity(name = "旧日记", createdAt = NOW),
                )
            migrated.tagDao().insertLinks(
                listOf(com.diary.core.database.entity.DiaryTagEntity(1L, tagId)),
            )
            assertEquals(listOf(tagId), migrated.tagDao().tagIdsOf(1L))
            migrated.close()

            // 重开一次，确认迁移后的版本号与表结构稳定。
            val reopened =
                Room.databaseBuilder(context, DiaryDatabase::class.java, DB_NAME)
                    .addMigrations(*DiaryMigrations.ALL)
                    .allowMainThreadQueries()
                    .build()
            assertEquals(1, reopened.diaryDao().countAll())
            assertEquals(listOf(tagId), reopened.tagDao().tagIdsOf(1L))
            assertTrue(reopened.openHelper.readableDatabase.version >= 3)
            reopened.close()
        }

    private fun createVersion1Database() {
        val path = context.getDatabasePath(DB_NAME)
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diary` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT, `content_json` TEXT NOT NULL, `plain_text` TEXT NOT NULL, " +
                "`category_id` INTEGER NOT NULL, `font_id` INTEGER, `font_size_level` INTEGER, " +
                "`char_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_diary_category_id_created_at` " +
                "ON `diary` (`category_id`, `created_at`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_diary_deleted_at` ON `diary` (`deleted_at`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `is_system` INTEGER NOT NULL, " +
                "`created_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_category_sort_order` ON `category` (`sort_order`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `font_asset` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`display_name` TEXT NOT NULL, `file_name` TEXT NOT NULL, `file_size` INTEGER NOT NULL, " +
                "`source` INTEGER NOT NULL, `status` INTEGER NOT NULL, `imported_at` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_font_asset_file_name` " +
                "ON `font_asset` (`file_name`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS room_master_table " +
                "(id INTEGER PRIMARY KEY, identity_hash TEXT)",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES(42, '$V1_IDENTITY_HASH')",
        )
        db.execSQL(
            "INSERT OR REPLACE INTO category (id, name, sort_order, is_system, created_at) " +
                "VALUES (1, '未分类', 0, 1, $NOW)",
        )
        db.execSQL(
            "INSERT INTO diary (id, title, content_json, plain_text, category_id, font_id, " +
                "font_size_level, char_count, created_at, updated_at, deleted_at) " +
                "VALUES (1, '升级前的日记', '{\"blocks\":[]}', '历史正文', 1, NULL, NULL, 4, $NOW, $NOW, NULL)",
        )
        db.version = 1
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration_test.db"
        const val NOW = 1_700_000_000_000L

        /** 取自 schemas/1.json，Room 依赖该值判断是否需要迁移。 */
        const val V1_IDENTITY_HASH = "3af1158b36240dab80f0dbdc4725dd55"
    }
}

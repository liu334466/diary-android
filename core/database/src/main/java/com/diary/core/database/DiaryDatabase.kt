package com.diary.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.diary.core.database.dao.CategoryDao
import com.diary.core.database.dao.DiaryDao
import com.diary.core.database.dao.DiaryFtsDao
import com.diary.core.database.dao.FontAssetDao
import com.diary.core.database.dao.TagDao
import com.diary.core.database.entity.CategoryEntity
import com.diary.core.database.entity.DiaryEntity
import com.diary.core.database.entity.DiaryFtsEntity
import com.diary.core.database.entity.DiaryTagEntity
import com.diary.core.database.entity.FontAssetEntity
import com.diary.core.database.entity.TagEntity
import com.diary.core.model.DiaryLimits

/**
 * 本地数据库。Schema 通过 KSP 导出到 `core/database/schemas`，每次改表必须同步版本号与迁移。
 */
@Database(
    entities = [
        DiaryEntity::class,
        CategoryEntity::class,
        FontAssetEntity::class,
        DiaryFtsEntity::class,
        TagEntity::class,
        DiaryTagEntity::class,
    ],
    version = DiaryDatabase.VERSION,
    exportSchema = true,
)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao

    abstract fun categoryDao(): CategoryDao

    abstract fun fontAssetDao(): FontAssetDao

    abstract fun diaryFtsDao(): DiaryFtsDao

    abstract fun tagDao(): TagDao

    companion object {
        const val NAME = "diary.db"

        /** v2 新增 diary_fts 全文索引表（P1 全文搜索）；v3 新增标签表与关联表。 */
        const val VERSION = 3

        /**
         * 数据库首次创建时写入系统分类“未分类”，固定 id=1。
         */
        fun seedCallback(now: () -> Long = System::currentTimeMillis): RoomDatabase.Callback =
            object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO category (id, name, sort_order, is_system, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf(
                            DiaryLimits.UNCATEGORIZED_ID,
                            DiaryLimits.UNCATEGORIZED_NAME,
                            0,
                            1,
                            now(),
                        ),
                    )
                }
            }

        fun build(context: Context): DiaryDatabase =
            Room.databaseBuilder(context, DiaryDatabase::class.java, NAME)
                .addCallback(seedCallback())
                .addMigrations(*DiaryMigrations.ALL)
                .build()
    }
}

/**
 * 迁移框架：新增版本时在此登记 `Migration(old, new)`，并补充升级测试。
 */
object DiaryMigrations {
    /**
     * v1 → v2：新增 FTS4 索引表。
     * 索引内容依赖 Kotlin 分词，迁移内只建表，历史数据由 `SearchIndexManager` 在启动后补建。
     */
    val MIGRATION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `diary_fts` USING FTS4(" +
                        "`title_tokens` TEXT NOT NULL, `body_tokens` TEXT NOT NULL, tokenize=unicode61)",
                )
            }
        }

    /**
     * v2 → v3：新增标签表与日记-标签关联表。
     */
    val MIGRATION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `created_at` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name` ON `tag` (`name`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `diary_tag` (`diary_id` INTEGER NOT NULL, " +
                        "`tag_id` INTEGER NOT NULL, PRIMARY KEY(`diary_id`, `tag_id`), " +
                        "FOREIGN KEY(`diary_id`) REFERENCES `diary`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tag_id`) REFERENCES `tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_diary_tag_tag_id` ON `diary_tag` (`tag_id`)",
                )
            }
        }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
}

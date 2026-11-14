package com.diary.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diary.core.database.repository.CategoryRepository
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.FontRepository
import com.diary.core.model.DiaryLimits
import com.diary.core.richtext.RichTextBlock
import com.diary.core.richtext.RichTextCodec
import com.diary.core.richtext.RichTextDocument
import com.diary.core.richtext.RichTextSpan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiaryDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: DiaryDatabase
    private lateinit var diaryRepository: DiaryRepository
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var fontRepository: FontRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java)
                .addCallback(DiaryDatabase.seedCallback { NOW })
                .allowMainThreadQueries()
                .build()
        diaryRepository =
            DiaryRepository(
                database.diaryDao(),
                database.categoryDao(),
                database.diaryFtsDao(),
                database,
            )
        categoryRepository =
            CategoryRepository(database.categoryDao(), database.diaryDao(), database)
        fontRepository =
            FontRepository(database.fontAssetDao(), database.diaryDao(), database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(REOPEN_DB)
    }

    @Test
    fun `首次建库写入系统未分类`() =
        runTest {
            val categories = categoryRepository.getAll()

            assertEquals(1, categories.size)
            val uncategorized = categories.first()
            assertEquals(DiaryLimits.UNCATEGORIZED_ID, uncategorized.id)
            assertEquals("未分类", uncategorized.name)
            assertTrue(uncategorized.isSystem)
        }

    @Test
    fun `软删除从列表隐藏并进入回收站`() =
        runTest {
            val id = diaryRepository.create(now = NOW)

            assertEquals(1, diaryRepository.observeActive().first().size)

            diaryRepository.softDelete(id, NOW + 1_000)

            assertEquals(0, diaryRepository.observeActive().first().size)
            assertEquals(1, diaryRepository.observeTrash().first().size)
            assertEquals(1, diaryRepository.observeTrashCount().first())
            assertNotNull(diaryRepository.getById(id)?.deletedAt)
        }

    @Test
    fun `恢复时原分类不存在则回到未分类`() =
        runTest {
            val categoryId =
                (
                    categoryRepository.create("工作") as
                        CategoryRepository.CategoryResult.Success
                ).id
            val diaryId = diaryRepository.create(categoryId, NOW)

            // 模拟分类记录已丢失的异常数据。
            database.categoryDao().deleteById(categoryId)
            diaryRepository.softDelete(diaryId, NOW + 1_000)

            assertTrue(diaryRepository.restore(diaryId, NOW + 2_000))

            val restored = diaryRepository.getById(diaryId)!!
            assertNull(restored.deletedAt)
            assertEquals(DiaryLimits.UNCATEGORIZED_ID, restored.categoryId)
        }

    @Test
    fun `过期清理只删除超过 30 天的记录`() =
        runTest {
            val overdue = diaryRepository.create(now = NOW - 40 * DAY)
            val recent = diaryRepository.create(now = NOW - 10 * DAY)
            diaryRepository.softDelete(overdue, NOW - 31 * DAY)
            diaryRepository.softDelete(recent, NOW - DAY)

            assertEquals(1, diaryRepository.purgeExpired(NOW))

            assertEquals(1, diaryRepository.observeTrash().first().size)
            assertNull(diaryRepository.getById(overdue))
            assertNotNull(diaryRepository.getById(recent))
        }

    @Test
    fun `回收站剩余天数向上取整`() {
        assertEquals(0, diaryRepository.trashRemainingDays(null, NOW))
        assertEquals(20, diaryRepository.trashRemainingDays(NOW - 10 * DAY, NOW))
        assertEquals(1, diaryRepository.trashRemainingDays(NOW - 29 * DAY, NOW))
        assertEquals(0, diaryRepository.trashRemainingDays(NOW - 30 * DAY, NOW))
    }

    @Test
    fun `删除分类把正常与回收站日记都迁移到未分类`() =
        runTest {
            val categoryId =
                (
                    categoryRepository.create("读书") as
                        CategoryRepository.CategoryResult.Success
                ).id
            val active = diaryRepository.create(categoryId, NOW)
            val trashed = diaryRepository.create(categoryId, NOW)
            diaryRepository.softDelete(trashed, NOW + 1_000)

            assertTrue(categoryRepository.delete(categoryId))

            assertEquals(DiaryLimits.UNCATEGORIZED_ID, diaryRepository.getById(active)?.categoryId)
            assertEquals(DiaryLimits.UNCATEGORIZED_ID, diaryRepository.getById(trashed)?.categoryId)
            assertNull(categoryRepository.getById(categoryId))
        }

    @Test
    fun `系统分类不可删除不可重命名`() =
        runTest {
            val id = DiaryLimits.UNCATEGORIZED_ID

            assertFalse(categoryRepository.delete(id))
            assertEquals(
                CategoryRepository.CategoryResult.SystemCategory,
                categoryRepository.rename(id, "新名字"),
            )
        }

    @Test
    fun `分类名称与数量上限校验`() =
        runTest {
            assertEquals(
                CategoryRepository.CategoryResult.NameEmpty,
                categoryRepository.create("   "),
            )
            assertEquals(
                CategoryRepository.CategoryResult.NameTooLong,
                categoryRepository.create("一二三四五六七八九十一二三"),
            )

            repeat(DiaryLimits.MAX_CATEGORIES) { index ->
                assertTrue(
                    categoryRepository.create("分类$index") is CategoryRepository.CategoryResult.Success,
                )
            }
            assertEquals(
                CategoryRepository.CategoryResult.LimitReached,
                categoryRepository.create("超出上限"),
            )
            assertTrue(categoryRepository.hasDuplicateName("分类0"))
        }

    @Test
    fun `删除字体清空单篇字体并移除正文引用片段`() =
        runTest {
            val fontId = fontRepository.registerImported("测试字体", "font_test.ttf", 1024, NOW)
            val diaryId = diaryRepository.create(now = NOW)
            val content =
                RichTextCodec.encode(
                    RichTextDocument(
                        blocks =
                            listOf(
                                RichTextBlock(
                                    text = "abcdef",
                                    spans =
                                        listOf(
                                            RichTextSpan(0, 3, fontId = fontId),
                                            RichTextSpan(0, 3, bold = true),
                                            RichTextSpan(3, 6, fontId = 999L),
                                        ),
                                ),
                            ),
                    ),
                )
            diaryRepository.saveContent(diaryId, "标题", content, NOW)
            diaryRepository.updateFont(diaryId, fontId, 5, NOW)

            assertNotNull(fontRepository.delete(fontId))

            val diary = diaryRepository.getById(diaryId)!!
            assertNull(diary.fontId)
            val spans = RichTextCodec.decode(diary.contentJson).blocks.first().spans
            assertEquals(2, spans.size)
            assertEquals(true, spans[0].bold)
            assertEquals(999L, spans[1].fontId)
            assertNull(fontRepository.getById(fontId))
        }

    @Test
    fun `内置字体不可删除`() =
        runTest {
            val id =
                database.fontAssetDao().insert(
                    com.diary.core.database.entity.FontAssetEntity(
                        displayName = "内置",
                        fileName = "builtin.ttf",
                        fileSize = 1024,
                        source = 0,
                        status = 0,
                        importedAt = NOW,
                    ),
                )

            assertNull(fontRepository.delete(id))
            assertNotNull(fontRepository.getById(id))
        }

    @Test
    fun `保存正文同步纯文本字数并清洗标题`() =
        runTest {
            val diaryId = diaryRepository.create(now = NOW)
            val content =
                RichTextCodec.encode(
                    RichTextDocument(
                        blocks =
                            listOf(
                                RichTextBlock(text = "你好 世界"),
                                RichTextBlock(text = "第二行"),
                            ),
                    ),
                )

            diaryRepository.saveContent(diaryId, "   ", content, NOW)
            val untitled = diaryRepository.getById(diaryId)!!
            assertNull(untitled.title)
            assertEquals("你好 世界\n第二行", untitled.plainText)
            assertEquals(7, untitled.charCount)

            diaryRepository.saveContent(diaryId, "长".repeat(60), content, NOW)
            assertEquals(50, diaryRepository.getById(diaryId)!!.title?.length)
        }

    @Test
    fun `数据库关闭重开后数据仍在`() =
        runTest {
            context.deleteDatabase(REOPEN_DB)
            var fileDatabase =
                Room.databaseBuilder(context, DiaryDatabase::class.java, REOPEN_DB)
                    .addCallback(DiaryDatabase.seedCallback { NOW })
                    .build()
            val repo =
                DiaryRepository(
                    fileDatabase.diaryDao(),
                    fileDatabase.categoryDao(),
                    fileDatabase.diaryFtsDao(),
                    fileDatabase,
                )
            val id = repo.create(now = NOW)
            repo.saveContent(id, "重开验证", RichTextCodec.emptyJson(), NOW)
            fileDatabase.close()

            fileDatabase =
                Room.databaseBuilder(context, DiaryDatabase::class.java, REOPEN_DB)
                    .addCallback(DiaryDatabase.seedCallback { NOW })
                    .build()
            val reopened = fileDatabase.diaryDao().getById(id)
            fileDatabase.close()

            assertNotNull(reopened)
            assertEquals("重开验证", reopened!!.title)
            assertTrue(File(context.getDatabasePath(REOPEN_DB).absolutePath).exists())
        }

    @Test
    fun `全文搜索命中标题与正文并过滤回收站`() =
        runTest {
            val rain = diaryRepository.create(now = NOW)
            diaryRepository.saveContent(rain, "周末的雨", body("雨下了一整天，没出门。"), NOW)
            val book = diaryRepository.create(now = NOW)
            diaryRepository.saveContent(book, "读书笔记", body("把没读完的书看完了。"), NOW)

            assertEquals(listOf(rain), diaryRepository.search("雨").map { it.id })
            assertEquals(listOf(book), diaryRepository.search("读书").map { it.id })
            assertEquals(2, diaryRepository.search("没").size)

            diaryRepository.softDelete(rain, NOW + 1_000)
            assertEquals(0, diaryRepository.search("雨").size)

            diaryRepository.deletePermanently(book)
            assertEquals(0, diaryRepository.search("读书").size)
        }

    @Test
    fun `更新正文后旧关键词不再命中`() =
        runTest {
            val id = diaryRepository.create(now = NOW)
            diaryRepository.saveContent(id, "标题", body("第一版内容"), NOW)
            assertEquals(1, diaryRepository.search("第一版").size)

            diaryRepository.saveContent(id, "标题", body("第二版内容"), NOW + 1_000)
            assertEquals(0, diaryRepository.search("第一版").size)
            assertEquals(1, diaryRepository.search("第二版").size)
        }

    @Test
    fun `清空回收站同时清理全文索引`() =
        runTest {
            val id = diaryRepository.create(now = NOW)
            diaryRepository.saveContent(id, "待删除", body("临时内容"), NOW)
            diaryRepository.softDelete(id, NOW + 1_000)

            diaryRepository.clearTrash()

            assertEquals(0, database.diaryFtsDao().count())
            assertEquals(0, diaryRepository.search("临时").size)
        }

    private fun body(text: String): String =
        RichTextCodec.encode(RichTextDocument(blocks = listOf(RichTextBlock(text = text))))

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAY = 24L * 60L * 60L * 1000L
        const val REOPEN_DB = "reopen_test.db"
    }
}

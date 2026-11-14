package com.diary.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diary.core.database.repository.DiaryRepository
import com.diary.core.database.repository.TagRepository
import com.diary.core.model.DiaryLimits
import com.diary.core.richtext.RichTextBlock
import com.diary.core.richtext.RichTextCodec
import com.diary.core.richtext.RichTextDocument
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 标签体系：标签库维护、单篇绑定覆盖写、按标签筛选与级联清理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TagRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: DiaryDatabase
    private lateinit var tagRepository: TagRepository
    private lateinit var diaryRepository: DiaryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database =
            Room.inMemoryDatabaseBuilder(context, DiaryDatabase::class.java)
                .addCallback(DiaryDatabase.seedCallback { NOW })
                .allowMainThreadQueries()
                .build()
        tagRepository = TagRepository(database.tagDao(), database)
        diaryRepository =
            DiaryRepository(
                database.diaryDao(),
                database.categoryDao(),
                database.diaryFtsDao(),
                database,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `新建标签裁掉空白且重名复用已有记录`() =
        runTest {
            val first = tagRepository.createTag("  旅行  ", NOW) as TagRepository.TagResult.Success
            assertEquals("旅行", first.tag.name)

            val again = tagRepository.createTag("旅行", NOW + 1_000) as TagRepository.TagResult.Success
            assertEquals(first.tag.id, again.tag.id)
            assertEquals(1, database.tagDao().count())
        }

    @Test
    fun `标签名称与数量上限校验`() =
        runTest {
            assertEquals(
                TagRepository.TagResult.NameEmpty,
                tagRepository.createTag("   "),
            )
            assertEquals(
                TagRepository.TagResult.NameTooLong,
                tagRepository.createTag("标".repeat(DiaryLimits.MAX_TAG_NAME_LENGTH + 1)),
            )

            repeat(DiaryLimits.MAX_TAGS) { index ->
                assertTrue(
                    tagRepository.createTag("标签$index", NOW + index) is
                        TagRepository.TagResult.Success,
                )
            }
            assertEquals(
                TagRepository.TagResult.LimitReached,
                tagRepository.createTag("再新建一个"),
            )
        }

    @Test
    fun `覆盖式绑定标签并超出单篇上限时截断`() =
        runTest {
            val diaryId = diaryRepository.create(now = NOW)
            val ids =
                (0 until DiaryLimits.MAX_TAGS_PER_DIARY + 2).map { index ->
                    (
                        tagRepository.createTag("标签$index", NOW + index) as
                            TagRepository.TagResult.Success
                    ).tag.id
                }

            tagRepository.setTags(diaryId, ids)
            assertEquals(
                ids.take(DiaryLimits.MAX_TAGS_PER_DIARY).sorted(),
                tagRepository.tagsOf(diaryId),
            )

            // 再次提交应整体覆盖，而不是追加。
            tagRepository.setTags(diaryId, listOf(ids.first(), ids.first()))
            assertEquals(listOf(ids.first()), tagRepository.tagsOf(diaryId))
            assertEquals(1, tagRepository.observeLinks().first()[diaryId]?.size)
        }

    @Test
    fun `按标签筛选只返回未删除日记`() =
        runTest {
            val travel =
                (
                    tagRepository.createTag("旅行", NOW) as
                        TagRepository.TagResult.Success
                ).tag
            val active = diaryRepository.create(now = NOW)
            val trashed = diaryRepository.create(now = NOW)
            diaryRepository.saveContent(active, "在路上的第一天", body("出发"), NOW)
            diaryRepository.saveContent(trashed, "已删除的旅行", body("旧内容"), NOW)
            tagRepository.setTags(active, listOf(travel.id))
            tagRepository.setTags(trashed, listOf(travel.id))
            diaryRepository.softDelete(trashed, NOW + 1_000)

            assertEquals(2, tagRepository.usageOf(travel.id))
            assertEquals(listOf(active), tagRepository.diariesWithTag(travel.id).map { it.id })
        }

    @Test
    fun `删除标签级联清理关联但保留日记`() =
        runTest {
            val tag =
                (
                    tagRepository.createTag("读书", NOW) as
                        TagRepository.TagResult.Success
                ).tag
            val diaryId = diaryRepository.create(now = NOW)
            tagRepository.setTags(diaryId, listOf(tag.id))

            tagRepository.deleteTag(tag.id)

            assertNull(database.tagDao().getById(tag.id))
            assertEquals(0, tagRepository.tagsOf(diaryId).size)
            assertNotNull(diaryRepository.getById(diaryId))
        }

    @Test
    fun `物理删除日记级联清理标签关联`() =
        runTest {
            val tag =
                (
                    tagRepository.createTag("工作", NOW) as
                        TagRepository.TagResult.Success
                ).tag
            val diaryId = diaryRepository.create(now = NOW)
            tagRepository.setTags(diaryId, listOf(tag.id))

            diaryRepository.deletePermanently(diaryId)

            assertEquals(0, tagRepository.usageOf(tag.id))
            assertNotNull(database.tagDao().getById(tag.id))
        }

    @Test
    fun `重命名标签校验空值长度与重名`() =
        runTest {
            val travel = (tagRepository.createTag("旅行", NOW) as TagRepository.TagResult.Success).tag
            val work = (tagRepository.createTag("工作", NOW) as TagRepository.TagResult.Success).tag

            val renamed =
                tagRepository.renameTag(travel.id, "  出行  ") as TagRepository.TagResult.Success
            assertEquals("出行", renamed.tag.name)
            assertEquals("出行", database.tagDao().getById(travel.id)?.name)

            assertEquals(TagRepository.TagResult.NameEmpty, tagRepository.renameTag(travel.id, " "))
            assertEquals(
                TagRepository.TagResult.NameTooLong,
                tagRepository.renameTag(travel.id, "标".repeat(DiaryLimits.MAX_TAG_NAME_LENGTH + 1)),
            )
            assertEquals(TagRepository.TagResult.NameDuplicate, tagRepository.renameTag(travel.id, work.name))
            assertEquals("出行", database.tagDao().getById(travel.id)?.name)
        }

    @Test
    fun `标签使用量按日记统计`() =
        runTest {
            val tag = (tagRepository.createTag("读书", NOW) as TagRepository.TagResult.Success).tag
            val first = diaryRepository.create(now = NOW)
            val second = diaryRepository.create(now = NOW)
            tagRepository.setTags(first, listOf(tag.id))
            tagRepository.setTags(second, listOf(tag.id))

            assertEquals(2, tagRepository.observeUsage().first()[tag.id])

            tagRepository.deleteTag(tag.id)
            assertEquals(0, tagRepository.observeUsage().first()[tag.id] ?: 0)
        }

    private fun body(text: String): String =
        RichTextCodec.encode(RichTextDocument(blocks = listOf(RichTextBlock(text = text))))

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
